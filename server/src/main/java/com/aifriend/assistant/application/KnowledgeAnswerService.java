package com.aifriend.assistant.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import org.springframework.dao.DataAccessException;

import com.aifriend.assistant.domain.AssistantAnswer;
import com.aifriend.assistant.domain.AssistantAnswer.Mode;
import com.aifriend.assistant.domain.AssistantAnswer.Status;
import com.aifriend.assistant.domain.AssistantReason;
import com.aifriend.retrieval.application.*;
import com.aifriend.retrieval.application.KnowledgeQuotaPort.Decision;
import com.aifriend.retrieval.application.KnowledgeQuotaPort.Phase;
import com.aifriend.retrieval.domain.KnowledgeAnswerDraft;
import com.aifriend.retrieval.domain.IndexVersion;
import com.aifriend.retrieval.domain.RetrievalQuery;
import com.aifriend.retrieval.domain.RetrievalResult;

/**
 * 公开知识回答编排；不创建联系任务，不接触私人图谱，不在数据库事务内调用模型。
 * 会话层须先持久化请求ID/截止/profile，并在缓存重放时另行复验授权和来源。
 * @author codex
 * @since 1.0.0
 */
public final class KnowledgeAnswerService {
    private final KnowledgeRepositoryPort repository;
    private final KnowledgeSearchPort search;
    private final RankFusionPort fusion;
    private final Optional<EmbeddingPort> embedding;
    private final Optional<KnowledgeAnswerGenerationPort> generation;
    private final KnowledgeQuotaPort quota;
    private final KnowledgeAccessPolicy access;
    private final Settings settings;
    private final Clock clock;
    private final LongSupplier ticker;
    private final AnswerValidator validator = new AnswerValidator();
    private final KnownAnswerRiskPolicy risks = new KnownAnswerRiskPolicy();

    /**
     * 使用不可变依赖创建服务；构造本身不执行外部调用。
     * @param repository 公开知识权威来源
     * @param search 本地关键词/向量搜索
     * @param fusion 排名融合
     * @param embedding 独立在线Embedding端口，关闭时empty
     * @param generation 独立Chat端口，关闭时empty
     * @param quota 持久额度预留
     * @param access 独立用途同意
     * @param settings 固定服务器回答模式
     * @param clock UTC时钟，截止由会话持久化
     */
    public KnowledgeAnswerService(KnowledgeRepositoryPort repository, KnowledgeSearchPort search,
            RankFusionPort fusion, Optional<EmbeddingPort> embedding,
            Optional<KnowledgeAnswerGenerationPort> generation, KnowledgeQuotaPort quota,
            KnowledgeAccessPolicy access, Settings settings, Clock clock) {
        this(repository, search, fusion, embedding, generation, quota, access, settings, clock, System::nanoTime);
    }

    KnowledgeAnswerService(KnowledgeRepositoryPort repository, KnowledgeSearchPort search,
            RankFusionPort fusion, Optional<EmbeddingPort> embedding,
            Optional<KnowledgeAnswerGenerationPort> generation, KnowledgeQuotaPort quota,
            KnowledgeAccessPolicy access, Settings settings, Clock clock, LongSupplier ticker) {
        this.repository = Objects.requireNonNull(repository);
        this.search = Objects.requireNonNull(search);
        this.fusion = Objects.requireNonNull(fusion);
        this.embedding = Objects.requireNonNull(embedding);
        this.generation = Objects.requireNonNull(generation);
        this.quota = Objects.requireNonNull(quota);
        this.access = Objects.requireNonNull(access);
        this.settings = Objects.requireNonNull(settings);
        this.clock = Objects.requireNonNull(clock);
        this.ticker = Objects.requireNonNull(ticker);
    }

    /**
     * 单个逻辑问题：最多一次查询向量、两次生成（网络重试与协议修复共享上限）。
     * @param request 已认证会话层创建的请求，不直接绑定客户端owner/profile/截止
     * @return 当前事实结果；摘录明确标记EVIDENCE_ONLY
     */
    public AssistantAnswer answer(Request request) {
        return answerWithEvidence(request).answer();
    }

    /**
     * 为持久会话保留实际检索世代；缓存重放不能只保存回答文本而丢失复验依据。
     * @param request 会话层冻结请求
     * @return 回答及其实际来源世代，不产生额外模型调用
     */
    public AnswerResult answerWithEvidence(Request request) {
        Objects.requireNonNull(request);
        var deadline = request.executionBudget().map(KnowledgeAnswerDeadline::new)
                .orElseGet(() -> new KnowledgeAnswerDeadline(request.deadline(), clock, ticker));
        try {
            deadline.check();
            if (request.external()) { access.requireExternalModelConsent(request.owner()); }
            deadline.check();
            Decision admission = reserve(request, Phase.REQUEST, "LOCAL", 1);
            deadline.check();
            if (admission != Decision.GRANTED) { return unavailable(quotaReason(admission)); }
            if (risks.containsInstruction(request.query().text()) || request.history().turns().stream()
                    .anyMatch(turn -> risks.containsInstruction(turn.question()) || risks.containsInstruction(turn.answerSummary()))) {
                return unavailable(AssistantReason.UNTRUSTED_INSTRUCTION);
            }
            if (risks.requestsUngroundedAnswer(request.query().text())) { return noEvidence(); }
            var contextualQuery = PublicKnowledgeContext.resolve(request.query(), request.history());
            if (contextualQuery.isEmpty()) {
                return new AnswerResult(new AssistantAnswer(AssistantAnswer.Purpose.PUBLIC_KNOWLEDGE,
                        Status.NEEDS_CLARIFICATION, Mode.NONE, AssistantReason.MISSING_CONTEXT,
                        "请说出具体想了解的功能，例如：小友守护怎么关闭？", List.of(), List.of()),
                        Optional.empty(), Optional.empty());
            }
            var lane = new QueryLane(request, deadline);
            Optional<EmbeddingPort> guarded = embedding.map(port -> lane.guard(port));
            var retrieval = new HybridRetrievalService(repository, search, fusion, guarded, Duration.ofSeconds(2))
                    .retrieve(contextualQuery.orElseThrow());
            deadline.check();
            if (retrieval.evidence().isEmpty()) {
                return finish(request, deadline, retrieval, noEvidence().answer());
            }
            var risk = risks.evidenceRisk(retrieval);
            if (risk != KnownAnswerRiskPolicy.Risk.NONE) { return unavailable(riskReason(risk)); }
            if (settings.mode() == Mode.EXTRACTIVE || !request.external() || lane.reason != AssistantReason.NONE) {
                return finish(request, deadline, retrieval, extract(retrieval, lane.reason));
            }
            if (generation.isEmpty() || request.generationProfile().isEmpty()) {
                return finish(request, deadline, retrieval, extract(retrieval, AssistantReason.MODEL_CONFIGURATION));
            }
            return generate(request, deadline, retrieval);
        } catch (KnowledgeAnswerDeadline.Expired expired) {
            return unavailable(AssistantReason.SESSION_EXPIRED);
        } catch (KnowledgeRetrievalException failure) {
            return failure.kind() == KnowledgeRetrievalException.Kind.NO_INDEX ? noEvidence()
                    : unavailable(failure.kind() == KnowledgeRetrievalException.Kind.SOURCE_CHANGED
                        ? AssistantReason.EVIDENCE_INVALIDATED : AssistantReason.INDEX_INVALID);
        } catch (DataAccessException storage) {
            return unavailable(AssistantReason.STORAGE_UNAVAILABLE);
        } catch (KnowledgeGatewayException unavailable) {
            return unavailable(gatewayReason(unavailable.kind()));
        }
        // BusinessException（同意/身份）和CancellationException不得被catch后降级或重试。
    }

    private AnswerResult generate(Request request, KnowledgeAnswerDeadline deadline, RetrievalResult retrieval) {
        var model = generation.orElseThrow();
        AssistantReason reason = AssistantReason.MODEL_TEMPORARY;
        boolean repair = false;
        for (int attempt = 1; attempt <= 2; attempt++) {
            deadline.check();
            access.requireExternalModelConsent(request.owner());
            if (!request.generationProfile().orElseThrow().equals(model.profileId())) {
                return unavailable(AssistantReason.PROFILE_CHANGED);
            }
            deadline.check();
            Decision permission = reserve(request, Phase.ANSWER_GENERATION, model.profileId(), attempt);
            deadline.check();
            if (permission != Decision.GRANTED) {
                return finish(request, deadline, retrieval, extract(retrieval, quotaReason(permission)));
            }
            // 预留可能阻塞或遇到撤权；真正外发前再次复验，且不退款/换身份重试。
            access.requireExternalModelConsent(request.owner());
            if (!request.generationProfile().orElseThrow().equals(model.profileId())) {
                return unavailable(AssistantReason.PROFILE_CHANGED);
            }
            Duration budget = deadline.remaining(settings.generationBudget());
            try {
                var draft = request.history().turns().isEmpty()
                        ? model.generate(request.query().text(), retrieval, repair, budget)
                        : model.generate(request.query().text(), retrieval, request.history(), repair, budget);
                deadline.check();
                validator.validate(draft, retrieval);
                if (draft.status() == KnowledgeAnswerDraft.Status.NO_EVIDENCE) {
                    return finish(request, deadline, retrieval, noEvidence().answer());
                }
                var risk = risks.answerRisk(draft, retrieval);
                if (risk != KnownAnswerRiskPolicy.Risk.NONE) {
                    return finish(request, deadline, retrieval, extract(retrieval, riskReason(risk)));
                }
                String text = draft.sentences().stream().map(sentence ->
                        sentence.text() + " [" + String.join(",", sentence.evidenceIds()) + "]")
                        .collect(Collectors.joining("\n"));
                // 引用列表保持原e1..e4顺序；不能压缩列表造成e3误指向第一个来源。
                var answer = new AssistantAnswer(AssistantAnswer.Purpose.PUBLIC_KNOWLEDGE,
                        Status.ANSWERED, Mode.GENERATED, AssistantReason.NONE, text, retrieval.evidence(), List.of());
                return finish(request, deadline, retrieval, answer);
            } catch (KnowledgeGatewayException failure) {
                reason = gatewayReason(failure.kind());
                if (failure.kind() != KnowledgeGatewayException.Kind.PROTOCOL
                        && failure.kind() != KnowledgeGatewayException.Kind.TEMPORARY) { break; }
                repair = failure.kind() == KnowledgeGatewayException.Kind.PROTOCOL;
            }
        }
        return finish(request, deadline, retrieval, extract(retrieval, reason));
    }

    private AnswerResult finish(Request request, KnowledgeAnswerDeadline deadline,
            RetrievalResult retrieval, AssistantAnswer answer) {
        deadline.check();
        if (!repository.isCurrent(retrieval.indexVersion(),
                retrieval.evidence().stream().map(e -> e.chunk()).toList())) {
            return unavailable(AssistantReason.EVIDENCE_INVALIDATED);
        }
        if (request.external()) { access.requireExternalModelConsent(request.owner()); }
        deadline.check();
        if (request.external() && settings.mode() == Mode.GENERATED && generation.isPresent()
                && request.generationProfile().isPresent()
                && !request.generationProfile().orElseThrow().equals(generation.orElseThrow().profileId())) {
            return unavailable(AssistantReason.PROFILE_CHANGED);
        }
        return new AnswerResult(answer, Optional.of(retrieval.indexVersion()), Optional.of(retrieval.mode()));
    }

    private Decision reserve(Request request, Phase phase, String profile, int attempt) {
        return Objects.requireNonNull(quota.reserve(new KnowledgeQuotaPort.Reservation(request.operationId(),
                Optional.of(request.owner()), phase, profile, attempt, 0, request.deadline())));
    }

    private static AssistantAnswer extract(RetrievalResult retrieval, AssistantReason reason) {
        var text = new StringBuilder("找到以下原文说明：");
        for (int i = 0; i < retrieval.evidence().size(); i++) {
            text.append("\n[").append('e').append(i + 1).append("] ")
                    .append(retrieval.evidence().get(i).chunk().text());
        }
        return new AssistantAnswer(AssistantAnswer.Purpose.PUBLIC_KNOWLEDGE, Status.EVIDENCE_ONLY,
                Mode.EXTRACTIVE, reason, text.toString(), retrieval.evidence(), List.of());
    }

    private static AnswerResult unavailable(AssistantReason reason) {
        return new AnswerResult(new AssistantAnswer(AssistantAnswer.Purpose.PUBLIC_KNOWLEDGE, Status.UNAVAILABLE,
                Mode.NONE, reason, "暂时无法回答，请稍后重试。", List.of(), List.of()), Optional.empty(), Optional.empty());
    }

    private static AnswerResult noEvidence() {
        return new AnswerResult(new AssistantAnswer(AssistantAnswer.Purpose.PUBLIC_KNOWLEDGE, Status.NO_EVIDENCE,
                Mode.NONE, AssistantReason.NO_SUPPORT, "没有找到足够的相关说明。", List.of(), List.of()), Optional.empty(), Optional.empty());
    }

    private static AssistantReason gatewayReason(KnowledgeGatewayException.Kind kind) {
        return switch (kind) {
            case CONFIGURATION -> AssistantReason.MODEL_CONFIGURATION;
            case PROTOCOL -> AssistantReason.MODEL_PROTOCOL;
            case TEMPORARY, BUSY -> AssistantReason.MODEL_TEMPORARY;
            case ENDPOINT_REJECTED -> AssistantReason.ENDPOINT_REJECTED;
        };
    }

    private static AssistantReason quotaReason(Decision decision) {
        return switch (decision) {
            case DUPLICATE -> AssistantReason.RESULT_STALE;
            case EXPIRED -> AssistantReason.SESSION_EXPIRED;
            case DISABLED, LIMIT_EXCEEDED -> AssistantReason.BUDGET_EXHAUSTED;
            case GRANTED -> throw new IllegalArgumentException("GRANTED_IS_NOT_FAILURE");
        };
    }

    private static AssistantReason riskReason(KnownAnswerRiskPolicy.Risk risk) {
        return switch (risk) {
            case UNTRUSTED_INSTRUCTION -> AssistantReason.UNTRUSTED_INSTRUCTION;
            case CONFLICTING_SOURCES -> AssistantReason.CONFLICTING_SOURCES;
            case UNSUPPORTED_ANSWER -> AssistantReason.UNSUPPORTED_ANSWER;
            case NONE -> AssistantReason.NONE;
        };
    }

    /** 每次answer私有的状态，不跨请求共享错误或调用资格。 */
    private final class QueryLane {
        private final Request request;
        private final KnowledgeAnswerDeadline deadline;
        private AssistantReason reason = AssistantReason.NONE;
        QueryLane(Request request, KnowledgeAnswerDeadline deadline) {
            this.request = request; this.deadline = deadline;
        }
        EmbeddingPort guard(EmbeddingPort port) {
            return (profile, texts, ignoredBudget) -> {
                deadline.check();
                access.requireExternalModelConsent(request.owner());
                deadline.check();
                var permission = reserve(request, Phase.QUERY_EMBEDDING, profile.id(), 1);
                deadline.check();
                if (permission != Decision.GRANTED) {
                    reason = quotaReason(permission);
                    throw new KnowledgeGatewayException(KnowledgeGatewayException.Kind.BUSY);
                }
                access.requireExternalModelConsent(request.owner());
                Duration budget = deadline.remaining(Duration.ofSeconds(2));
                try {
                    var batch = port.embed(profile, texts, budget);
                    deadline.check();
                    return batch;
                } catch (KnowledgeGatewayException failure) {
                    reason = gatewayReason(failure.kind());
                    throw failure;
                }
            };
        }
    }

    /**
     * 内部持久化材料；API不将它解释为执行证明。
     * @param answer 固定用途回答
     * @param evidenceVersion 实际世代，用于后续缓存/幂等结果权威复验
     * @param retrievalMode 实际检索通道，不以关键词降级冒充双路成功
     */
    public record AnswerResult(AssistantAnswer answer, Optional<IndexVersion> evidenceVersion,
            Optional<RetrievalResult.Mode> retrievalMode) {
        /** 有引用必有世代，失败不得携带可重用来源。 */
        public AnswerResult {
            Objects.requireNonNull(answer);
            Objects.requireNonNull(evidenceVersion);
            Objects.requireNonNull(retrievalMode);
            if (answer.purpose() != AssistantAnswer.Purpose.PUBLIC_KNOWLEDGE
                    || (!answer.citations().isEmpty() && evidenceVersion.isEmpty())
                    || (answer.status() == Status.UNAVAILABLE && evidenceVersion.isPresent())
                    || evidenceVersion.isPresent() != retrievalMode.isPresent()) {
                throw new IllegalArgumentException("INVALID_KNOWLEDGE_ANSWER_RESULT");
            }
        }
        @Override public String toString() { return "KnowledgeAnswerResult[values=redacted]"; }
    }

    /**
     * 服务端固定模式，不能由每次用户请求改变。
     * @param mode EXTRACTIVE或GENERATED
     * @param generationBudget 每次生成1ms～4s，仍服从问题8s总期限
     */
    public record Settings(Mode mode, Duration generationBudget) {
        /** 拒绝模板/无模式及无界预算。 */
        public Settings {
            if ((mode != Mode.EXTRACTIVE && mode != Mode.GENERATED) || generationBudget == null
                    || generationBudget.compareTo(Duration.ofMillis(1)) < 0
                    || generationBudget.compareTo(Duration.ofSeconds(4)) > 0) {
                throw new IllegalArgumentException("INVALID_ANSWER_SETTINGS");
            }
        }
    }

    /**
     * 已由会话层冻结的请求；重放必须保留ID、截止和profile，不能直接绑定客户端字段。
     * @param owner 认证主体
     * @param operationId 持久逻辑请求ID
     * @param query 只包含公开知识问题
     * @param deadline 持久毫秒级UTC截止
     * @param generationProfile 冻结的生成配置标识
     * @param history 会话层已复验的公开历史，不是本轮证据；不能携带图谱历史
     * @param executionBudget 生产会话共享的进程预算，数据库额度仍使用deadline
     */
    public record Request(UUID owner, UUID operationId, RetrievalQuery query,
            Instant deadline, Optional<String> generationProfile, com.aifriend.assistant.domain.AssistantConversation history,
            Optional<AssistantExecutionPort.Budget> executionBudget) {
        /**
         * 同一时钟域的兼容入口，生产会话必须显式提供共享预算。
         * @param owner 认证主体
         * @param operationId 持久请求ID
         * @param query 公开问题
         * @param deadline 原数据库截止
         * @param generationProfile 固定生成配置
         * @param history 已复验历史
         */
        public Request(UUID owner,UUID operationId,RetrievalQuery query,Instant deadline,Optional<String> generationProfile,
                com.aifriend.assistant.domain.AssistantConversation history) {
            this(owner,operationId,query,deadline,generationProfile,history,Optional.empty());
        }
        /**
         * 单轮兼容入口，明确无历史。
         * @param owner 认证主体
         * @param operationId 持久请求ID
         * @param query 当前问题
         * @param deadline 原总截止
         * @param generationProfile 固定生成配置
         */
        public Request(UUID owner,UUID operationId,RetrievalQuery query,Instant deadline,Optional<String> generationProfile) {
            this(owner,operationId,query,deadline,generationProfile,new com.aifriend.assistant.domain.AssistantConversation(
                    AssistantAnswer.Purpose.PUBLIC_KNOWLEDGE,List.of()));
        }
        /** 校验内部契约。 */
        public Request {
            Objects.requireNonNull(owner);
            Objects.requireNonNull(operationId);
            Objects.requireNonNull(query);
            Objects.requireNonNull(deadline);
            Objects.requireNonNull(generationProfile);
            Objects.requireNonNull(history);
            Objects.requireNonNull(executionBudget);
            if(history.purpose()!=AssistantAnswer.Purpose.PUBLIC_KNOWLEDGE) throw new IllegalArgumentException("PRIVATE_CONTEXT_FORBIDDEN");
            if (deadline.getNano() % 1_000_000 != 0
                    || generationProfile.filter(p -> !p.matches("[A-Za-z0-9._:-]{1,100}")).isPresent()) {
                throw new IllegalArgumentException("INVALID_ANSWER_REQUEST");
            }
        }
        boolean external() { return query.externalProcessing() == RetrievalQuery.ExternalProcessing.ALLOWED; }
        @Override public String toString() { return "KnowledgeAnswerRequest[values=redacted]"; }
    }
}
