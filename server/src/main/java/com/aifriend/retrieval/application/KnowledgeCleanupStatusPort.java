package com.aifriend.retrieval.application;

import java.time.Instant;
import java.util.Objects;

/**
 * 管理员只读回收积压投影；不认领任务、不更新退避和告警回执，不返回身份或租约令牌。
 * @author Codex
 * @since 1.0.0
 */
public interface KnowledgeCleanupStatusPort {
    /**
     * 读取当前回收积压，不认领或执行回收。
     * @return 同一数据库语句快照中的匿名计数；存储不可用不得伪造空积压
     */
    Snapshot read();

    /** 不是永久清除承诺，只描述本次观察到的待办。 */
    enum BacklogState {
        /** 观察到待办、失败或未结束租约。 */
        CLEANUP_PENDING,
        /** 本次未观察到积压，不保证未来无新增待办。 */
        NO_BACKLOG_OBSERVED
    }
    /** 只暴露租约生命周期，不暴露令牌或拥有者。 */
    enum LeaseState {
        /** 没有批次租约。 */
        NONE,
        /** 批次租约尚未到期。 */
        ACTIVE,
        /** 租约已到期，需维护流程回收。 */
        EXPIRED
    }

    /**
     * 匿名有界只读快照。
     * @param observedAt 本次数据库UTC时间
     * @param firstFailureAt 当前失败周期起点，无失败为null
     * @param nextAttemptAt 账本记录的最早可尝试时间，不保证扫描器已经运行
     * @param failures 当前失败次数，清理部分成功不清零
     * @param leaseState 租约生命周期
     * @param inactiveVersions 未清原文的非活动版本数，包含尚被引用/构建的版本
     * @param inactiveGenerations 非活动世代数，包含尚未允许回收的世代
     * @param unreferencedChunks 无清单引用的片段数，仍可能被待办保护
     * @param countsTruncated 任一计数超过10000时标记为下界
     * @param pendingFirstAlerts 未验证送达的首次失败告警数量
     * @param pendingOverdueAlerts 未验证送达的超时告警数量
     */
    record Snapshot(Instant observedAt, Instant firstFailureAt, Instant nextAttemptAt, int failures,
            LeaseState leaseState, int inactiveVersions, int inactiveGenerations, int unreferencedChunks,
            boolean countsTruncated, long pendingFirstAlerts, long pendingOverdueAlerts) {
        /** 拒绝坏状态，不将基础设施错误解释为空积压。 */
        public Snapshot {
            Objects.requireNonNull(observedAt, "observedAt");
            Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
            Objects.requireNonNull(leaseState, "leaseState");
            if (failures < 0 || (failures == 0) != (firstFailureAt == null)
                    || (firstFailureAt != null && firstFailureAt.isAfter(observedAt))
                    || inactiveVersions < 0 || inactiveVersions > 10000
                    || inactiveGenerations < 0 || inactiveGenerations > 10000
                    || unreferencedChunks < 0 || unreferencedChunks > 10000
                    || (countsTruncated && Math.max(inactiveVersions, Math.max(inactiveGenerations, unreferencedChunks)) != 10000)
                    || pendingFirstAlerts < 0 || pendingOverdueAlerts < 0) {
                throw new IllegalArgumentException("INVALID_CLEANUP_STATUS");
            }
        }

        /**
         * 根据匿名快照推导保守积压状态。
         * @return 存在待办/失败/租约时保守返回待清理，不把无失败当已清空
         */
        public BacklogState state() {
            return inactiveVersions > 0 || inactiveGenerations > 0 || unreferencedChunks > 0
                    || failures > 0 || leaseState != LeaseState.NONE
                    ? BacklogState.CLEANUP_PENDING : BacklogState.NO_BACKLOG_OBSERVED;
        }

        /**
         * 判断失败周期是否达到告警升级期限。
         * @return 当前失败周期已满15分钟；不代表告警已发送或已受理
         */
        public boolean overdue() {
            return firstFailureAt != null && !observedAt.isBefore(firstFailureAt.plusSeconds(900));
        }
    }
}
