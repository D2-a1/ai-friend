package com.aifriend.retrieval.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 导入任务的持久化状态转换；时间必须来自存储适配器的数据库时钟。
 * 本对象不能代替数据库全局租约和版本条件更新。
 * @param id 任务标识
 * @param state 有限状态
 * @param attempts 已认领次数（跨进程保留）
 * @param version 乐观锁版本
 * @param leaseToken 当前认领令牌
 * @param leaseUntil 独占租约截止（到点失效）
 * @param nextAttemptAt 下次允许认领时间
 * @param deadline 全任务截止
 * @param failure 固定错误原因
 * @author codex
 * @since 1.0.0
 */
public record KnowledgeImportJob(UUID id, State state, int attempts, long version,
        Optional<UUID> leaseToken, Optional<Instant> leaseUntil, Instant nextAttemptAt,
        Instant deadline, Failure failure) {
    /** 最大总认领次数，不随进程重启或临时失败重置。 */
    public static final int MAX_ATTEMPTS = 3;

    /** 导入状态，不把已受理当作索引可用。 */
    public enum State {
        /** 已登记，等待到期认领。 */
        PENDING,
        /** 正在有效租约保护下构建。 */
        PROCESSING,
        /** 本任务已成功发布。 */
        READY,
        /** 已终止，不能自动重新认领。 */
        FAILED
    }

    /** 可持久化错误枚举，不保存模型原文或SQL异常。 */
    public enum Failure {
        /** 没有错误。 */
        NONE,
        /** 可按剩余预算退避的临时故障。 */
        TEMPORARY,
        /** 构建配置不满足要求。 */
        CONFIGURATION,
        /** 索引完整性或结构校验未通过。 */
        INDEX_INVALID,
        /** 原始文档版本已变化。 */
        SOURCE_CHANGED,
        /** 当前认领租约已到期。 */
        LEASE_EXPIRED,
        /** 持久化总尝试次数已耗尽。 */
        ATTEMPTS_EXHAUSTED,
        /** 全任务截止时间已到。 */
        DEADLINE,
        /** 资源或费用预算不足。 */
        RESOURCE_LIMIT
    }

    /** 校验存储重建的状态，损坏记录不能被认领。 */
    public KnowledgeImportJob {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(leaseToken, "leaseToken");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(failure, "failure");
        boolean processing = state == State.PROCESSING;
        if (attempts < 0 || attempts > MAX_ATTEMPTS || version < 1
                || processing != leaseToken.isPresent() || processing != leaseUntil.isPresent()
                || (processing && (attempts == 0 || leaseUntil.orElseThrow().isAfter(deadline)))
                || (state == State.PENDING && attempts >= MAX_ATTEMPTS)
                || (state == State.READY && (attempts == 0 || failure != Failure.NONE))
                || (state == State.FAILED && failure == Failure.NONE)) {
            throw new IllegalArgumentException("INVALID_IMPORT_JOB");
        }
    }

    /**
     * 创建尚未认领的任务。
     * @param id 任务标识
     * @param now DB时间
     * @param deadline 有界总截止
     * @return 待处理任务
     */
    public static KnowledgeImportJob pending(UUID id, Instant now, Instant deadline) {
        Objects.requireNonNull(now, "now");
        if (deadline == null || !now.isBefore(deadline)) {
            throw new IllegalArgumentException("INVALID_IMPORT_DEADLINE");
        }
        return new KnowledgeImportJob(id, State.PENDING, 0, 1, Optional.empty(), Optional.empty(),
                now, deadline, Failure.NONE);
    }

    /**
     * 首次认领或回收崩溃进程的过期租约。数据库必须另外持有全局控制行锁。
     * @param now DB时间
     * @param token 新随机令牌
     * @param duration 租约（不超过5分钟，也不超过任务截止）
     * @return 新状态，调用方以旧version条件持久化
     */
    public KnowledgeImportJob claim(Instant now, UUID token, Duration duration) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(token, "token");
        if (duration == null || duration.isNegative() || duration.isZero()
                || duration.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("INVALID_IMPORT_LEASE");
        }
        boolean expired = state == State.PROCESSING && !now.isBefore(leaseUntil.orElseThrow());
        if ((state != State.PENDING && !expired) || attempts >= MAX_ATTEMPTS
                || now.isBefore(nextAttemptAt) || !now.isBefore(deadline)
                || leaseToken.filter(token::equals).isPresent()) {
            throw new IllegalStateException("IMPORT_NOT_CLAIMABLE");
        }
        Instant until = now.plus(duration);
        if (until.isAfter(deadline)) { until = deadline; }
        return new KnowledgeImportJob(id, State.PROCESSING, attempts + 1, Math.addExact(version, 1),
                Optional.of(token), Optional.of(until), now, deadline, Failure.NONE);
    }

    /**
     * 发布后转READY；必须与索引激活及全局控制行CAS在同一事务。
     * @param now DB时间
     * @param token 当前工作进程令牌
     * @return READY状态
     */
    public KnowledgeImportJob complete(Instant now, UUID token) {
        requireLease(now, token);
        return terminal(State.READY, Failure.NONE);
    }

    /**
     * 处理可恢复失败；第3次失败成为终态，不能再排队。
     * @param now DB时间
     * @param token 当前令牌
     * @param reason 固定原因
     * @param retryable 是否仅为可恢复故障
     * @return 延迟待处理或失败终态
     */
    public KnowledgeImportJob fail(Instant now, UUID token, Failure reason, boolean retryable) {
        requireLease(now, token);
        if (reason == null || reason == Failure.NONE) {
            throw new IllegalArgumentException("INVALID_IMPORT_FAILURE");
        }
        if (retryable && reason != Failure.TEMPORARY && reason != Failure.SOURCE_CHANGED) {
            throw new IllegalArgumentException("IMPORT_FAILURE_NOT_RETRYABLE");
        }
        Instant next = now.plusSeconds(attempts == 1 ? 1 : 5);
        if (!retryable || attempts == MAX_ATTEMPTS || !next.isBefore(deadline)) {
            return terminal(State.FAILED, reason);
        }
        return new KnowledgeImportJob(id, State.PENDING, attempts, Math.addExact(version, 1),
                Optional.empty(), Optional.empty(), next, deadline, reason);
    }

    /**
     * 扫描器收敛任务截止或最后一次崩溃；不能取消仍有效的租约。
     * @param now DB时间
     * @return 确实到期时的失败终态，否则原对象
     */
    public KnowledgeImportJob expire(Instant now) {
        Objects.requireNonNull(now, "now");
        if (state == State.READY || state == State.FAILED) { return this; }
        if (!now.isBefore(deadline)) { return terminal(State.FAILED, Failure.DEADLINE); }
        if (state == State.PROCESSING && attempts == MAX_ATTEMPTS
                && !now.isBefore(leaseUntil.orElseThrow())) {
            return terminal(State.FAILED, Failure.ATTEMPTS_EXHAUSTED);
        }
        return this;
    }

    private void requireLease(Instant now, UUID token) {
        if (now == null || state != State.PROCESSING || !leaseToken.orElseThrow().equals(token)
                || !now.isBefore(leaseUntil.orElseThrow()) || !now.isBefore(deadline)) {
            throw new IllegalStateException("IMPORT_LEASE_LOST");
        }
    }

    private KnowledgeImportJob terminal(State terminal, Failure reason) {
        return new KnowledgeImportJob(id, terminal, attempts, Math.addExact(version, 1),
                Optional.empty(), Optional.empty(), nextAttemptAt, deadline, reason);
    }

    @Override public String toString() {
        return "KnowledgeImportJob[state=" + state + ", attempts=" + attempts + ", version=" + version + "]";
    }
}
