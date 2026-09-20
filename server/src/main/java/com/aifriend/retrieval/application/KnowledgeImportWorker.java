package com.aifriend.retrieval.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;

import com.aifriend.retrieval.application.KnowledgeGenerationPort.Build;
import com.aifriend.retrieval.application.KnowledgeImportLeasePort.Claim;
import com.aifriend.retrieval.application.KnowledgeImportRegistrationPort.BuildSpecification;
import com.aifriend.retrieval.application.KnowledgeQuotaPort.Decision;
import com.aifriend.retrieval.application.KnowledgeQuotaPort.Phase;
import com.aifriend.retrieval.application.KnowledgeQuotaPort.Reservation;
import com.aifriend.retrieval.domain.KnowledgeChunk;
import com.aifriend.retrieval.domain.KnowledgeImportJob;
import com.aifriend.retrieval.domain.KnowledgeImportJob.Failure;

/**
 * 每次扫描最多处理一个持久任务，网络调用与数据库事务分离。
 * 未知数据库/提交错误向上传播，不盲写FAILED或重复执行模型。
 * @author codex
 * @since 1.0.0
 */
public final class KnowledgeImportWorker {
    private final KnowledgeImportWorkPort work;
    private final KnowledgeImportLeasePort leases;
    private final KnowledgeBuildSourcePort sources;
    private final KnowledgeGenerationPort generations;
    private final KnowledgeChunkingPort chunker;
    private final KnowledgeQuotaPort quota;
    private final Optional<EmbeddingPort> embedding;
    private final Settings settings;

    /** 单次受控执行结果；不把待办/重试当作发布成功。 */
    public enum Outcome {
        /** 本次没有可认领任务。 */
        NO_WORK,
        /** 本次构建已成功发布。 */
        READY,
        /** 本次任务已记录终止失败。 */
        FAILED,
        /** 本次暂缓，尚未发布成功。 */
        DEFERRED
    }

    /**
     * 构造纯编排服务；后台独立模型实例由条件配置注入。
     * @param work 待办和DB剩余租约
     * @param leases 全局认领
     * @param sources 完整来源
     * @param generations 暂存及发布
     * @param chunker 版本化分块
     * @param quota 持久费用预留
     * @param embedding 独立后台向量端口，词法模式为空
     * @param settings 固定构建配置
     */
    public KnowledgeImportWorker(KnowledgeImportWorkPort work, KnowledgeImportLeasePort leases,
            KnowledgeBuildSourcePort sources, KnowledgeGenerationPort generations, KnowledgeChunkingPort chunker,
            KnowledgeQuotaPort quota, Optional<EmbeddingPort> embedding, Settings settings) {
        this.work = Objects.requireNonNull(work, "work");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.generations = Objects.requireNonNull(generations, "generations");
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        this.quota = Objects.requireNonNull(quota, "quota");
        this.embedding = Objects.requireNonNull(embedding, "embedding");
        this.settings = Objects.requireNonNull(settings, "settings");
        if (!chunker.version().equals(settings.specification().chunkerVersion())
                || embedding.isPresent() != settings.specification().embeddingProfile().isPresent()) {
            throw new IllegalArgumentException("IMPORT_CONFIGURATION_MISMATCH");
        }
    }

    /**
     * 执行一次扫描，不递归重试，不创建无界队列。
     * @return 单任务结果
     */
    public Outcome tick() {
        checkInterrupted();
        List<UUID> due = work.due(1);
        if (due.size() > 1) { throw new IllegalArgumentException("INVALID_WORK_SCAN"); }
        if (due.isEmpty()) { return Outcome.NO_WORK; }
        var claimed = leases.claim(due.get(0), UUID.randomUUID(), settings.lease());
        if (claimed.isEmpty()) { return Outcome.NO_WORK; }
        Claim claim = claimed.orElseThrow();
        try {
            build(claim);
            return Outcome.READY;
        } catch (BuildFailure failure) {
            return fail(claim, failure.reason, failure.retryable);
        } catch (KnowledgeGatewayException gateway) {
            return switch (gateway.kind()) {
                case BUSY, TEMPORARY -> fail(claim, Failure.TEMPORARY, true);
                case CONFIGURATION, ENDPOINT_REJECTED -> fail(claim, Failure.CONFIGURATION, false);
                case PROTOCOL -> fail(claim, Failure.INDEX_INVALID, false);
            };
        } catch (IllegalArgumentException invalid) {
            return fail(claim, Failure.INDEX_INVALID, false);
        }
    }

    private void build(Claim claim) {
        checkInterrupted();
        var source = sources.load(claim);
        if (!source.claim().equals(claim) || !source.specification().equals(settings.specification())) {
            throw new BuildFailure(Failure.CONFIGURATION, false);
        }
        long sourceBytes = source.documents().stream().mapToLong(d -> (long) d.text().length() * 8 + 1024).sum();
        requireMemory(sourceBytes + 16L * 1024 * 1024);
        var chunks = new ArrayList<KnowledgeChunk>();
        for (var document : source.documents()) {
            checkInterrupted();
            var parts = chunker.chunk(document);
            if (parts.size() > 2000 - chunks.size()) { throw new BuildFailure(Failure.RESOURCE_LIMIT, false); }
            chunks.addAll(parts);
        }
        int dimension = source.specification().embeddingProfile().map(p -> p.dimension()).orElse(0);
        long chunkBytes = chunks.stream().mapToLong(c -> c.text().length() * 8L + 1024L + dimension * 24L).sum();
        // 两份字符串/索引及float+归一化向量的保守预算；不是JVM实际峰值测量。
        requireMemory(sourceBytes + chunkBytes + 16L * 1024 * 1024);
        var build = new Build(source, source.snapshot(UUID.randomUUID(), chunks));
        generations.begin(build);
        int sequence = 0;
        for (int from = 0; from < chunks.size(); from += settings.batchSize()) {
            checkInterrupted();
            var batch = List.copyOf(chunks.subList(from, Math.min(chunks.size(), from + settings.batchSize())));
            var vectors = new HashMap<UUID, float[]>();
            work.remaining(claim);
            if (embedding.isPresent()) {
                var profile = settings.specification().embeddingProfile().orElseThrow();
                Decision permit = quota.reserve(new Reservation(claim.job().id(), Optional.empty(), Phase.IMPORT_EMBEDDING,
                        profile.id(), claim.job().attempts(), sequence, claim.job().deadline()));
                if (permit != Decision.GRANTED) { throw new BuildFailure(Failure.RESOURCE_LIMIT, false); }
                checkInterrupted();
                Duration remaining = work.remaining(claim);
                Duration budget = remaining.compareTo(settings.modelCallBudget()) < 0 ? remaining : settings.modelCallBudget();
                if (budget.compareTo(Duration.ofMillis(1)) < 0) { throw new BuildFailure(Failure.DEADLINE, false); }
                long started = System.nanoTime();
                var result = embedding.orElseThrow().embed(profile, batch.stream().map(KnowledgeChunk::text).toList(), budget);
                if (System.nanoTime() - started >= budget.toNanos()) { throw new BuildFailure(Failure.TEMPORARY, true); }
                checkInterrupted();
                if (result == null || !result.profile().equals(profile) || result.size() != batch.size()) {
                    throw new BuildFailure(Failure.INDEX_INVALID, false);
                }
                for (int i = 0; i < batch.size(); i++) { vectors.put(batch.get(i).id(), result.vector(i)); }
            }
            generations.stage(build, batch.stream().map(KnowledgeChunk::id).toList(), vectors);
            sequence++;
        }
        checkInterrupted();
        work.remaining(claim);
        generations.publish(build);
    }

    private Outcome fail(Claim claim, Failure reason, boolean retryable) {
        var saved = leases.fail(claim, reason, retryable);
        return saved.state() == KnowledgeImportJob.State.PENDING ? Outcome.DEFERRED : Outcome.FAILED;
    }
    private void requireMemory(long bytes) {
        if (bytes > settings.maximumSnapshotBytes()) { throw new BuildFailure(Failure.RESOURCE_LIMIT, false); }
    }
    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) { throw new CancellationException("IMPORT_INTERRUPTED"); }
    }

    /**
     * 固定构建参数，由外部类型安全配置创建。
     * @param specification 批准的算法/profile
     * @param lease 全局独占租约，最多5分钟
     * @param batchSize 1到64，不能超过模型适配器上限
     * @param maximumSnapshotBytes 最高128MiB，允许降低
     * @param modelCallBudget 单次网络预算，最多8秒
     */
    public record Settings(BuildSpecification specification, Duration lease, int batchSize,
            long maximumSnapshotBytes, Duration modelCallBudget) {
        /** 不接受可突破硬上限的配置。 */
        public Settings {
            Objects.requireNonNull(specification, "specification");
            if (lease == null || lease.compareTo(Duration.ofSeconds(1)) < 0 || lease.compareTo(Duration.ofMinutes(5)) > 0
                    || lease.toNanos() % 1_000_000 != 0 || batchSize < 1 || batchSize > 64
                    || maximumSnapshotBytes < 1024 * 1024 || maximumSnapshotBytes > 128L * 1024 * 1024
                    || modelCallBudget == null || modelCallBudget.compareTo(Duration.ofMillis(1)) < 0
                    || modelCallBudget.compareTo(Duration.ofSeconds(8)) > 0) {
                throw new IllegalArgumentException("INVALID_IMPORT_WORKER_SETTINGS");
            }
        }
    }
    private static final class BuildFailure extends RuntimeException {
        private final Failure reason;
        private final boolean retryable;
        private BuildFailure(Failure reason, boolean retryable) { super(reason.name()); this.reason = reason; this.retryable = retryable; }
    }
}
