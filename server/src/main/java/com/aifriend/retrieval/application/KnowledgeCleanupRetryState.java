package com.aifriend.retrieval.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.aifriend.retrieval.application.KnowledgeCleanupRetryPort.AlertLevel;
import com.aifriend.retrieval.application.KnowledgeCleanupRetryPort.Outcome;

/**
 * RC的纯状态转移；所有now必须由持有控制行锁的仓储提供数据库UTC时间。
 * 前三次失败分别退避1/5/15秒，之后每300秒继续，不丢弃待办。
 * 两类告警累计计数独立于当前清理周期，支持清理已恢复但通知通道仍故障。
 * @param failures 当前未排空周期的失败次数（饱和计数）
 * @param pendingSince 首次失败或失联租约到期时间
 * @param nextAttempt 最早下次尝试时间
 * @param lastSeen 持久时钟栅栏
 * @param leaseToken 当前批次随机令牌
 * @param leaseUntil 批次期限
 * @param escalated 当前周期是否已产生超15分钟告警
 * @param firstFailures 首次失败累计序号
 * @param firstAcknowledged 已验证送达序号
 * @param escalations 超时告警累计序号
 * @param escalationAcknowledged 超时告警已验证送达序号
 * @author Codex
 * @since 1.0.0
 */
public record KnowledgeCleanupRetryState(int failures, Instant pendingSince, Instant nextAttempt,
        Instant lastSeen, UUID leaseToken, Instant leaseUntil, boolean escalated,
        long firstFailures, long firstAcknowledged, long escalations, long escalationAcknowledged) {
    /** 拒绝损坏/不一致状态，不降级成无待办。 */
    public KnowledgeCleanupRetryState {
        Objects.requireNonNull(nextAttempt, "nextAttempt");
        Objects.requireNonNull(lastSeen, "lastSeen");
        if (failures < 0 || (failures == 0) != (pendingSince == null)
                || (pendingSince != null && pendingSince.isAfter(lastSeen))
                || (leaseToken == null) != (leaseUntil == null)
                || (leaseUntil != null && (leaseUntil.isBefore(lastSeen) || leaseUntil.isAfter(lastSeen.plusSeconds(30))))
                || (escalated && (pendingSince == null || lastSeen.isBefore(pendingSince.plusSeconds(900))))
                || firstFailures < 0 || firstAcknowledged < 0 || firstAcknowledged > firstFailures
                || escalations < 0 || escalationAcknowledged < 0 || escalationAcknowledged > escalations
                || escalations > firstFailures || (failures > 0 && firstFailures == 0)
                || (escalated && escalations == 0)) {
            throw new IllegalArgumentException("INVALID_CLEANUP_RETRY_STATE");
        }
    }

    /**
     * 创建初始状态。
     * @param now 数据库时间
     * @return 初始化状态，不能用于覆盖已有行
     */
    public static KnowledgeCleanupRetryState initial(Instant now) {
        return new KnowledgeCleanupRetryState(0, null, now, now, null, null, false, 0, 0, 0, 0);
    }

    /**
     * 回收失联租约并生成持久失败，达到15分钟时升级；不立即补跑清理。
     * @param now 持锁后取得的数据库时间
     * @return 推进后的状态
     */
    public KnowledgeCleanupRetryState observe(Instant now) {
        checkClock(now);
        boolean expired = leaseUntil != null && !now.isBefore(leaseUntil);
        int count = expired ? incrementFailures(failures) : failures;
        Instant since = expired && pendingSince == null ? leaseUntil : pendingSince;
        long first = expired && pendingSince == null ? Math.incrementExact(firstFailures) : firstFailures;
        boolean overdue = since != null && !now.isBefore(since.plusSeconds(900));
        long escalation = overdue && !escalated ? Math.incrementExact(escalations) : escalations;
        return new KnowledgeCleanupRetryState(count, since, expired ? now.plusSeconds(delay(count)) : nextAttempt,
                now, expired ? null : leaseToken, expired ? null : leaseUntil, escalated || overdue,
                first, firstAcknowledged, escalation, escalationAcknowledged);
    }

    /**
     * 尝试获得批次租约。
     * @param token 新令牌
     * @return 当前时刻有资格才写入租约，否则返回自身
     */
    public KnowledgeCleanupRetryState acquire(UUID token) {
        Objects.requireNonNull(token, "token");
        if (leaseToken != null || lastSeen.isBefore(nextAttempt)) { return this; }
        return new KnowledgeCleanupRetryState(failures, pendingSince, nextAttempt, lastSeen, token,
                lastSeen.plusSeconds(30), escalated, firstFailures, firstAcknowledged, escalations, escalationAcknowledged);
    }

    /**
     * 记录实际批次结论。
     * @param token 当前令牌
     * @param outcome 实际批次结论
     * @return 旧令牌无效，不能覆盖新周期
     */
    public KnowledgeCleanupRetryState finish(UUID token, Outcome outcome) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(outcome, "outcome");
        if (!token.equals(leaseToken)) { return this; }
        boolean drained = outcome == Outcome.DRAINED;
        boolean failed = outcome == Outcome.FAILED;
        int count = drained ? 0 : failed ? incrementFailures(failures) : failures;
        Instant since = drained ? null : failed && pendingSince == null ? lastSeen : pendingSince;
        long first = failed && pendingSince == null ? Math.incrementExact(firstFailures) : firstFailures;
        return new KnowledgeCleanupRetryState(count, since, lastSeen.plusSeconds(failed ? delay(count) : 1),
                lastSeen, null, null, drained ? false : escalated,
                first, firstAcknowledged, escalations, escalationAcknowledged);
    }

    /**
     * 单调推进已验证回执。
     * @param level 已验证类别
     * @param sequence 已验证累计序号
     * @return 新状态，未来/旧回执无效
     */
    public KnowledgeCleanupRetryState acknowledge(AlertLevel level, long sequence) {
        Objects.requireNonNull(level, "level");
        long latest = level == AlertLevel.FIRST_FAILURE ? firstFailures : escalations;
        long acknowledged = level == AlertLevel.FIRST_FAILURE ? firstAcknowledged : escalationAcknowledged;
        if (sequence <= acknowledged || sequence > latest) { return this; }
        return new KnowledgeCleanupRetryState(failures, pendingSince, nextAttempt, lastSeen, leaseToken,
                leaseUntil, escalated, firstFailures, level == AlertLevel.FIRST_FAILURE ? sequence : firstAcknowledged,
                escalations, level == AlertLevel.OVERDUE ? sequence : escalationAcknowledged);
    }

    private void checkClock(Instant now) {
        if (now == null || now.isBefore(lastSeen)) { throw new IllegalStateException("CLEANUP_CLOCK_UNRELIABLE"); }
    }

    private static int incrementFailures(int count) { return count == Integer.MAX_VALUE ? count : count + 1; }
    private static long delay(int count) {
        return switch (count) { case 1 -> 1; case 2 -> 5; case 3 -> 15; default -> 300; };
    }
}
