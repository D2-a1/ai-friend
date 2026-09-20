package com.aifriend.retention.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 任务历史清除作业持久化端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface TaskHistoryDeletionRepositoryPort {
    /**
     * 可靠受理或幂等重放清除作业。
     *
     * @param ownerUserId owner UUID
     * @param keyHash 幂等键 SHA-256
     * @param requestHash 请求语义 SHA-256
     * @param now 受理时间
     * @return 当前进行中、幂等重放或新建作业的公开状态
     */
    TaskHistoryDeletionView accept(UUID ownerUserId, byte[] keyHash, byte[] requestHash, Instant now);
    /**
     * 查询 owner 最近一次作业。
     *
     * @param ownerUserId owner UUID
     * @return 最近一次公开状态
     */
    Optional<TaskHistoryDeletionView> findLatest(UUID ownerUserId);
    /**
     * 查询一批到期作业。
     *
     * @param now 当前时间
     * @param batchSize 最大数量
     * @return 有界作业列表
     */
    List<TaskHistoryDeletionJob> findReady(Instant now, int batchSize);
    /**
     * 记录失败并安排后续重试。
     *
     * @param jobId 作业 UUID
     * @param attemptedAt 本次尝试时间
     * @param nextAttemptAt 下次允许尝试时间
     */
    void markRetry(UUID jobId, Instant attemptedAt, Instant nextAttemptAt);
    /**
     * 在复验成功后完成作业。
     *
     * @param jobId 作业 UUID
     * @param now 完成时间
     */
    void markCompleted(UUID jobId, Instant now);
}