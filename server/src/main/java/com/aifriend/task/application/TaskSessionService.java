package com.aifriend.task.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.task.domain.TaskAction;
import com.aifriend.task.domain.TaskIntent;
import com.aifriend.task.domain.TaskOperationType;
import com.aifriend.task.domain.TaskState;
import com.aifriend.template.application.RoutineCommandLearningQueuePort;
import com.aifriend.template.application.RoutineCommandLearningRequest;
import com.aifriend.template.application.RoutineCommandTemplateStorePort;

/**
 * owner 范围任务查询、候选选择、动作型确认和渠道结果状态服务。
 *
 * <p>所有写操作按会话加锁并校验当前版本；取消和其他终态不能被迟到响应恢复。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class TaskSessionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskSessionService.class);
    private static final Duration CONFIRMATION_WINDOW = Duration.ofMinutes(2);
    private static final Duration CLOCK_FUTURE_TOLERANCE = Duration.ofSeconds(2);
    private static final Duration ACTION_PLAN_TTL = Duration.ofSeconds(30);

    private final TaskSessionRepositoryPort repositoryPort;
    private final RoutineCommandTemplateStorePort routineTemplateStorePort;
    private final RoutineCommandLearningQueuePort routineLearningQueuePort;
    private final TaskPayloadCodec payloadCodec;
    private final TaskSessionMapper mapper;
    private final DigestService digestService;
    private final WechatActionContactProjectionPort contactProjectionPort;
    private final WechatActionPlanProofIssuer proofIssuer;
    private final WechatExecutionProperties wechatExecutionProperties;
    private final DebugMvpDemoProperties debugMvpDemoProperties;
    private final Clock clock;

    /**
     * 创建任务会话状态服务。
     *
     * @param repositoryPort 任务会话持久化端口
     * @param routineTemplateStorePort 日常指令 owner 命名空间端口
     * @param routineLearningQueuePort 日常指令可靠学习 Outbox 端口
     * @param payloadCodec 敏感载荷保护器
     * @param mapper 会话响应映射器
     * @param digestService 摘要与常量时间比较服务
     * @param contactProjectionPort 已验证联系人稳定定位投影端口
     * @param proofIssuer 一次性定位证明签发器
     * @param wechatExecutionProperties 微信有限动作计划失败关闭配置
     * @param debugMvpDemoProperties Debug MVP 模拟完成配置
     * @param clock UTC 时钟
     */
    public TaskSessionService(
            TaskSessionRepositoryPort repositoryPort,
            RoutineCommandTemplateStorePort routineTemplateStorePort,
            RoutineCommandLearningQueuePort routineLearningQueuePort,
            TaskPayloadCodec payloadCodec,
            TaskSessionMapper mapper,
            DigestService digestService,
            WechatActionContactProjectionPort contactProjectionPort,
            WechatActionPlanProofIssuer proofIssuer,
            WechatExecutionProperties wechatExecutionProperties,
            DebugMvpDemoProperties debugMvpDemoProperties,
            Clock clock) {
        this.repositoryPort = repositoryPort;
        this.routineTemplateStorePort = routineTemplateStorePort;
        this.routineLearningQueuePort = routineLearningQueuePort;
        this.payloadCodec = payloadCodec;
        this.mapper = mapper;
        this.digestService = digestService;
        this.contactProjectionPort = contactProjectionPort;
        this.proofIssuer = proofIssuer;
        this.wechatExecutionProperties = wechatExecutionProperties;
        this.debugMvpDemoProperties = debugMvpDemoProperties;
        this.clock = clock;
    }

    /**
     * 查询当前 owner 的任务最新状态。
     *
     * @param ownerUserId JWT 派生 owner UUID
     * @param publicSessionId ts_ 前缀任务编号
     * @return 当前会话视图
     * @throws BusinessException 会话不存在或过期时抛出
     */
    @Transactional(readOnly = true)
    public TaskSessionView get(UUID ownerUserId, String publicSessionId) {
        TaskStoredSession session = repositoryPort.findByOwnerAndId(
                ownerUserId, PublicIdCodec.parseTaskSessionId(publicSessionId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        ensureNotExpired(session, Instant.now(clock));
        return mapper.toView(session);
    }

    /**
     * 查询当前 owner 最近二十条已结束任务的最小结果。
     *
     * @param ownerUserId JWT 派生 owner UUID
     * @return 按创建时间倒序排列的最小任务结果
     */
    @Transactional(readOnly = true)
    public List<RecentTaskResultView> listRecentResults(UUID ownerUserId) {
        return repositoryPort.listRecentResultsByOwner(ownerUserId).stream()
                .map(session -> {
                    TaskPayload payload = payloadCodec.decode(session.payloadCipher());
                    return new RecentTaskResultView(
                            PublicIdCodec.taskSessionId(session.id()),
                            session.state(),
                            payload.understanding().intent(),
                            session.createdAt(),
                            session.updatedAt());
                })
                .toList();
    }

    /**
     * 从当前会话最多三个候选中选择一个并重新生成完整复述。
     *
     * @param ownerUserId JWT 派生 owner UUID
     * @param publicSessionId ts_ 前缀任务编号
     * @param idempotencyKey 选择幂等键
     * @param command 候选编号和预期版本
     * @return 等待动作型确认的新版本会话
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskSessionView select(
            UUID ownerUserId,
            String publicSessionId,
            String idempotencyKey,
            SelectTaskCandidateCommand command) {
        validateSelection(command);
        UUID sessionId = PublicIdCodec.parseTaskSessionId(publicSessionId);
        byte[] keyHash = digestService.sha256(idempotencyKey);
        byte[] requestHash = digestService.sha256(
                command.candidateId() + "|" + command.expectedVersion());
        TaskStoredSession current = lock(ownerUserId, sessionId);
        TaskSessionView replay = replayIfCurrent(
                current, TaskOperationType.SELECTION, keyHash, requestHash);
        if (replay != null) {
            return replay;
        }
        Instant now = Instant.now(clock);
        ensureMutable(current, command.expectedVersion(),
                TaskState.AWAITING_SELECTION, now);
        TaskPayload payload = payloadCodec.decode(current.payloadCipher());
        TaskCandidateView selected = payload.candidates().stream()
                .filter(candidate -> candidate.candidateId().equals(command.candidateId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_CONFLICT));
        TaskIntent intent = payload.understanding().intent();
        String summary = summary(intent, selected.contact(),
                payload.understanding().messageText());
        String summaryHash = hashSummary(summary);
        TaskUnderstandingView understanding = new TaskUnderstandingView(
                intent, selected.contact(), payload.understanding().transcript(),
                payload.understanding().messageText(),
                payload.understanding().effectiveAudioRanges(),
                payload.understanding().corrections(),
                payload.understanding().confidence(),
                payload.understanding().processingVersions());
        TaskPayload updatedPayload = new TaskPayload(
                payload.context(), understanding, payload.candidates(), summary,
                confirmationActions(intent), now, null, null,
                payload.routineCommandLearningEvidence(),
                payload.conversationContext());
        TaskStoredSession updated = transition(
                current, TaskState.AWAITING_CONFIRMATION,
                payloadCodec.encode(updatedPayload),
                PublicIdCodec.parseContactId(selected.contact().id()),
                summaryHash, null, null, now);
        TaskStoredSession saved = repositoryPort.saveSession(updated);
        saveOperation(saved, TaskOperationType.SELECTION, keyHash, requestHash, now);
        return mapper.toView(saved);
    }

    /**
     * 用户听完系统生成的完整复述后，用普通“确认/否认”语音确认或拒绝任务。
     *
     * @param ownerUserId JWT 派生 owner UUID
     * @param publicSessionId ts_ 前缀任务编号
     * @param idempotencyKey 确认幂等键
     * @param command 动作、版本、摘要和语音确认时间
     * @return 更新会话及可空有限微信动作计划
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskConfirmationResult confirm(
            UUID ownerUserId,
            String publicSessionId,
            String idempotencyKey,
            ConfirmTaskCommand command) {
        validateConfirmation(command);
        UUID sessionId = PublicIdCodec.parseTaskSessionId(publicSessionId);
        byte[] keyHash = digestService.sha256(idempotencyKey);
        byte[] requestHash = digestService.sha256(command.action() + "|"
                + command.expectedVersion() + "|" + command.summaryHash() + "|"
                + command.confirmedAt());
        TaskStoredSession current = lock(ownerUserId, sessionId);
        TaskSessionView replay = replayIfCurrent(
                current, TaskOperationType.CONFIRMATION, keyHash, requestHash);
        if (replay != null) {
            TaskPayload replayPayload = payloadCodec.decode(current.payloadCipher());
            WechatActionPlanView replayPlan = replayPayload.actionPlan();
            if (replayPlan != null && (replayPlan.targetLocatorProof() == null
                    || replayPlan.targetSearchLocator() == null)) {
                throw new BusinessException(ErrorCode.ACTION_UNSUPPORTED);
            }
            return new TaskConfirmationResult(replay, replayPlan);
        }
        Instant now = Instant.now(clock);
        ensureMutable(current, command.expectedVersion(),
                TaskState.AWAITING_CONFIRMATION, now);
        TaskPayload payload = payloadCodec.decode(current.payloadCipher());
        verifySummary(current.summaryHash(), command.summaryHash());
        if (!payload.allowedActions().contains(command.action())) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        verifyConfirmationWindow(payload.confirmationStartedAt(), command.confirmedAt(), now);

        TaskState state;
        WechatActionPlanView plan = null;
        if (command.action() == TaskAction.CANCEL) {
            state = TaskState.CANCELLED;
        } else if (command.action() == TaskAction.REJECT) {
            state = TaskState.REJECTED;
        } else if (canSimulate(current, payload)) {
            state = TaskState.SIMULATED;
        } else {
            state = TaskState.EXECUTING;
            plan = createPlan(current, payload, command.action(), now);
        }
        TaskPayload updatedPayload = new TaskPayload(
                payload.context(), payload.understanding(), payload.candidates(),
                payload.spokenSummary(), Set.of(), payload.confirmationStartedAt(),
                plan, null, payload.routineCommandLearningEvidence(),
                payload.conversationContext());
        TaskStoredSession updated = transition(
                current, state, payloadCodec.encode(updatedPayload),
                current.selectedContactId(), current.summaryHash(),
                plan == null ? null : plan.planId(),
                plan == null ? null : plan.expiresAt(), now);
        TaskStoredSession saved = repositoryPort.saveSession(updated);
        saveOperation(saved, TaskOperationType.CONFIRMATION, keyHash, requestHash, now);
        enqueueRoutineLearning(saved, payload, state, now);
        return new TaskConfirmationResult(mapper.toView(saved), plan);
    }

    private boolean canSimulate(TaskStoredSession session, TaskPayload payload) {
        return debugMvpDemoProperties.enabled()
                && payload.context().basicRecognition() != null
                && session.selectedContactId() != null
                && contactProjectionPort.isDebugDemoContact(
                        session.ownerUserId(), session.selectedContactId());
    }

    /**
     * 归档与当前有限动作计划严格绑定的微信渠道结果。
     *
     * @param ownerUserId JWT 派生 owner UUID
     * @param publicSessionId ts_ 前缀任务编号
     * @param idempotencyKey 渠道结果幂等键
     * @param command 受控结果、规则版本和观察时间
     * @return 进入 COMPLETED、PARTIAL 或 FAILED 的终态会话
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskSessionView reportChannelResult(
            UUID ownerUserId,
            String publicSessionId,
            String idempotencyKey,
            ReportTaskChannelResultCommand command) {
        validateChannelCommand(command);
        UUID sessionId = PublicIdCodec.parseTaskSessionId(publicSessionId);
        byte[] keyHash = digestService.sha256(idempotencyKey);
        byte[] requestHash = digestService.sha256(command.planId() + "|"
                + command.summaryHash() + "|" + command.result() + "|"
                + command.parts() + "|" + command.ruleVersion() + "|"
                + command.occurredAt());
        TaskStoredSession current = lock(ownerUserId, sessionId);
        TaskSessionView replay = replayIfCurrent(
                current, TaskOperationType.CHANNEL_RESULT, keyHash, requestHash);
        if (replay != null) {
            return replay;
        }
        Instant now = Instant.now(clock);
        ensureMutable(current, current.sessionVersion(), TaskState.EXECUTING, now);
        TaskPayload payload = payloadCodec.decode(current.payloadCipher());
        WechatActionPlanView plan = payload.actionPlan();
        if (plan == null || !plan.planId().equals(command.planId())
                || !plan.summaryHash().equals(command.summaryHash())
                || !plan.minimumRuleVersion().equals(command.ruleVersion())
                || command.occurredAt().isAfter(now.plus(CLOCK_FUTURE_TOLERANCE))
                || command.occurredAt().isAfter(plan.expiresAt())) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        validateChannelEvidence(plan, command);
        TaskState state = channelState(command.result());
        TaskChannelResultView channelResult = new TaskChannelResultView(
                command.result(), command.parts(), command.occurredAt());
        TaskPayload updatedPayload = new TaskPayload(
                payload.context(), payload.understanding(), payload.candidates(),
                payload.spokenSummary(), Set.of(), payload.confirmationStartedAt(),
                plan, channelResult, payload.routineCommandLearningEvidence(),
                payload.conversationContext());
        TaskStoredSession updated = transition(
                current, state, payloadCodec.encode(updatedPayload),
                current.selectedContactId(), current.summaryHash(),
                current.planId(), current.planExpiresAt(), now);
        TaskStoredSession saved = repositoryPort.saveSession(updated);
        saveOperation(saved, TaskOperationType.CHANNEL_RESULT, keyHash, requestHash, now);
        return mapper.toView(saved);
    }

    private TaskStoredSession lock(UUID ownerUserId, UUID sessionId) {
        return repositoryPort.findByOwnerAndIdForUpdate(ownerUserId, sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private TaskSessionView replayIfCurrent(
            TaskStoredSession current,
            TaskOperationType type,
            byte[] keyHash,
            byte[] requestHash) {
        TaskStoredOperation operation = repositoryPort.findOperation(
                current.ownerUserId(), type, keyHash).orElse(null);
        if (operation == null) {
            return null;
        }
        boolean safe = operation.taskSessionId().equals(current.id())
                && operation.resultingSessionVersion() == current.sessionVersion()
                && digestService.constantTimeEquals(operation.requestHash(), requestHash);
        if (!safe) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        return mapper.toView(current);
    }

    private void enqueueRoutineLearning(
            TaskStoredSession saved,
            TaskPayload payload,
            TaskState state,
            Instant now) {
        RoutineCommandLearningEvidence evidence =
                payload.routineCommandLearningEvidence();
        if (state != TaskState.EXECUTING || evidence == null) {
            return;
        }
        TaskAudioRange range = evidence.actionAudioRange();
        TaskClientContext context = payload.context();
        boolean eligible = saved.selectedContactId() != null
                && evidence.intent() == payload.understanding().intent()
                && EnumSet.of(
                        TaskIntent.SEND_MESSAGE,
                        TaskIntent.VOICE_CALL,
                        TaskIntent.VIDEO_CALL).contains(evidence.intent())
                && range != null
                && range.startMs() >= 0
                && range.endMs() > range.startMs();
        if (!eligible) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        long namespaceVersion = routineTemplateStorePort.lockNamespace(
                saved.ownerUserId(), now);
        routineLearningQueuePort.enqueue(
                new RoutineCommandLearningRequest(
                        saved.id(),
                        saved.ownerUserId(),
                        saved.sourceAudioObjectId(),
                        evidence.intent(),
                        range.startMs(),
                        range.endMs(),
                        context.dialectCode(),
                        context.dialectPackageVersion(),
                        context.templateModelVersion(),
                        context.thresholdVersion()),
                namespaceVersion,
                now);
    }

    private void ensureMutable(
            TaskStoredSession current,
            long expectedVersion,
            TaskState expectedState,
            Instant now) {
        ensureNotExpired(current, now);
        if (current.state().terminal() || current.state() != expectedState
                || current.sessionVersion() != expectedVersion) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
    }

    private void ensureNotExpired(TaskStoredSession session, Instant now) {
        if (!session.expiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.SESSION_EXPIRED);
        }
    }

    private void verifySummary(String expected, String actual) {
        if (expected == null || actual == null || !digestService.constantTimeEquals(
                digestService.sha256(expected), digestService.sha256(actual))) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
    }

    private void verifyConfirmationWindow(Instant startedAt, Instant confirmedAt, Instant now) {
        if (startedAt == null || confirmedAt.isBefore(startedAt)
                || confirmedAt.isBefore(now.minus(CONFIRMATION_WINDOW))
                || confirmedAt.isAfter(now.plus(CLOCK_FUTURE_TOLERANCE))) {
            throw new BusinessException(ErrorCode.SESSION_EXPIRED);
        }
    }

    private WechatActionPlanView createPlan(
            TaskStoredSession session,
            TaskPayload payload,
            TaskAction action,
            Instant now) {
        TaskIntent intent = payload.understanding().intent();
        String planAction;
        if (action == TaskAction.CONFIRM_SEND && intent == TaskIntent.SEND_MESSAGE) {
            planAction = "SEND_AUDIO_AND_TEXT";
        } else if (action == TaskAction.CONFIRM_CALL && intent == TaskIntent.VOICE_CALL) {
            planAction = "START_VOICE_CALL";
        } else if (action == TaskAction.CONFIRM_CALL && intent == TaskIntent.VIDEO_CALL) {
            planAction = "START_VIDEO_CALL";
        } else {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        boolean messageAction = "SEND_AUDIO_AND_TEXT".equals(planAction);
        if (!wechatExecutionProperties.supports(payload.context(), planAction)) {
            LOGGER.info("Task action plan rejected stage=CLIENT_CAPABILITY action={}", planAction);
            throw new BusinessException(ErrorCode.ACTION_UNSUPPORTED);
        }
        if (session.selectedContactId() == null) {
            LOGGER.info("Task action plan rejected stage=CONTACT_SELECTION action={}", planAction);
            throw new BusinessException(ErrorCode.ACTION_UNSUPPORTED);
        }
        WechatActionContactSnapshot contact = contactProjectionPort.findVerifiedForUpdate(
                session.ownerUserId(), session.selectedContactId(),
                WechatSemanticCallContract.LOCATOR_VERSION)
                .orElseThrow(() -> {
                    LOGGER.info("Task action plan rejected stage=CONTACT_LOCATOR action={}",
                            planAction);
                    return new BusinessException(ErrorCode.ACTION_UNSUPPORTED);
                });
        String publicContactId = payload.understanding().contact().id();
        if (!PublicIdCodec.contactId(contact.contactId()).equals(publicContactId)) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        String planId = "wp_" + UUID.randomUUID().toString().replace("-", "");
        String audioObjectId = messageAction
                ? PublicIdCodec.audioObjectId(session.sourceAudioObjectId()) : null;
        Instant expiresAt = now.plus(ACTION_PLAN_TTL);
        WechatTargetLocatorProofView proof = proofIssuer.issue(
                new WechatActionPlanProofClaims(
                        planId, planAction, publicContactId, audioObjectId,
                        session.summaryHash(), payload.context().ruleVersion(),
                        contact.contactVersion(), payload.context().wechatVersion(),
                        contact.locatorVersion(), now, expiresAt),
                contact.stableLocator());
        LOGGER.info("Task action plan accepted action={}", planAction);
        return new WechatActionPlanView(
                planId, planAction, publicContactId, audioObjectId,
                session.summaryHash(), payload.context().ruleVersion(),
                expiresAt, contact.stableLocator(), proof);
    }

    private TaskStoredSession transition(
            TaskStoredSession current,
            TaskState state,
            byte[] payloadCipher,
            UUID selectedContactId,
            String summaryHash,
            String planId,
            Instant planExpiresAt,
            Instant now) {
        return new TaskStoredSession(
                current.id(), current.ownerUserId(), current.sourceAudioObjectId(),
                current.clientTaskId(), current.createIdempotencyKeyHash(),
                current.createRequestHash(), state, payloadCipher, selectedContactId,
                summaryHash, planId, planExpiresAt, current.sessionVersion() + 1,
                current.expiresAt(), current.createdAt(), now);
    }

    private void saveOperation(
            TaskStoredSession session,
            TaskOperationType type,
            byte[] keyHash,
            byte[] requestHash,
            Instant now) {
        repositoryPort.saveOperation(new TaskStoredOperation(
                UUID.randomUUID(), session.id(), session.ownerUserId(), type,
                keyHash, requestHash, session.sessionVersion(), now));
    }

    private void validateSelection(SelectTaskCandidateCommand command) {
        if (command == null || !within(command.candidateId(), 1, 64)
                || command.expectedVersion() < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private void validateConfirmation(ConfirmTaskCommand command) {
        if (command == null || command.action() == null
                || command.action() == TaskAction.SELECT_CANDIDATE
                || command.action() == TaskAction.RETRY
                || command.expectedVersion() < 1
                || !within(command.summaryHash(), 1, 100)
                || command.confirmedAt() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private void validateChannelCommand(ReportTaskChannelResultCommand command) {
        if (command == null || !within(command.planId(), 1, 64)
                || !within(command.summaryHash(), 1, 100)
                || !within(command.result(), 1, 30)
                || !within(command.ruleVersion(), 1, 40)
                || command.occurredAt() == null || command.parts().size() > 3) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Set<String> results = Set.of("OPENED", "HANDED_TO_WECHAT", "SENT",
                "PARTIAL", "CALL_STARTED", "UNSUPPORTED", "FAILED");
        if (!results.contains(command.result())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        for (TaskChannelPartView part : command.parts()) {
            if (part == null || !Set.of("AUDIO", "TEXT", "CALL").contains(part.part())
                    || !Set.of("HANDED_TO_WECHAT", "SENT", "CALL_STARTED",
                            "UNSUPPORTED", "FAILED").contains(part.result())
                    || (part.evidenceCode() != null && part.evidenceCode().length() > 80)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }
    }

    private void validateChannelEvidence(
            WechatActionPlanView plan,
            ReportTaskChannelResultCommand command) {
        if (WechatSemanticCallContract.RULE_VERSION.equals(plan.minimumRuleVersion())
                && ("CALL_STARTED".equals(command.result())
                        || command.parts().stream().anyMatch(part ->
                                "CALL_STARTED".equals(part.result())))) {
            throw new BusinessException(ErrorCode.ACTION_UNSUPPORTED);
        }
        if ("SENT".equals(command.result())) {
            boolean trusted = command.parts().stream().anyMatch(part ->
                    "SENT".equals(part.result()) && StringUtils.hasText(part.evidenceCode()));
            if (!trusted || !"SEND_AUDIO_AND_TEXT".equals(plan.action())) {
                throw new BusinessException(ErrorCode.ACTION_UNSUPPORTED);
            }
        }
        if ("CALL_STARTED".equals(command.result())) {
            boolean call = command.parts().stream().anyMatch(part ->
                    "CALL".equals(part.part()) && "CALL_STARTED".equals(part.result()));
            if (!call || "SEND_AUDIO_AND_TEXT".equals(plan.action())) {
                throw new BusinessException(ErrorCode.ACTION_UNSUPPORTED);
            }
        }
    }

    private TaskState channelState(String result) {
        return switch (result) {
            case "SENT", "CALL_STARTED" -> TaskState.COMPLETED;
            case "OPENED", "HANDED_TO_WECHAT", "PARTIAL" -> TaskState.PARTIAL;
            case "UNSUPPORTED", "FAILED" -> TaskState.FAILED;
            default -> throw new BusinessException(ErrorCode.ACTION_UNSUPPORTED);
        };
    }

    private Set<TaskAction> confirmationActions(TaskIntent intent) {
        return intent == TaskIntent.SEND_MESSAGE
                ? EnumSet.of(TaskAction.CONFIRM_SEND, TaskAction.REJECT, TaskAction.CANCEL)
                : EnumSet.of(TaskAction.CONFIRM_CALL, TaskAction.REJECT, TaskAction.CANCEL);
    }

    private String summary(TaskIntent intent, TaskMatchedContactView contact, String messageText) {
        String target = StringUtils.hasText(contact.displayName())
                ? contact.displayName() : contact.alias();
        String value = switch (intent) {
            case SEND_MESSAGE -> {
                if (!StringUtils.hasText(messageText)) {
                    throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
                }
                yield "给" + target + "发送消息：" + messageText;
            }
            case VOICE_CALL -> "给" + target + "发起微信语音通话";
            case VIDEO_CALL -> "给" + target + "发起微信视频通话";
            default -> throw new BusinessException(ErrorCode.ACTION_UNSUPPORTED);
        };
        if (value.length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return value;
    }

    private String hashSummary(String summary) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                digestService.sha256(summary.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean within(String value, int minimum, int maximum) {
        return value != null && value.length() >= minimum && value.length() <= maximum;
    }
}
