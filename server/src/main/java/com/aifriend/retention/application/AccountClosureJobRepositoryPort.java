package com.aifriend.retention.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 账号注销异步作业、重试和内部告警事实端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface AccountClosureJobRepositoryPort {

    /**
     * 查询一批允许执行的注销作业。
     *
     * @param now 当前 UTC 时间
     * @param batchSize 最大作业数
     * @return 有界作业列表
     */
    List<AccountClosureJob> findReady(Instant now, int batchSize);

    /**
     * 记录失败并安排后续重试。
     *
     * @param jobId 注销作业 UUID
     * @param attemptedAt 本次尝试时间
     * @param nextAttemptAt 下次允许尝试时间
     */
    void markRetry(UUID jobId, Instant attemptedAt, Instant nextAttemptAt);

    /**
     * 原子写入到期的 24 小时预警、48 小时 P0、15 分钟未接手升级和 72 小时超期事件。
     *
     * @param now 当前 UTC 时间
     * @param batchSize 最大扫描作业数
     * @return 本次新写入的告警事件数量
     * @throws IllegalArgumentException 当批次大小不在允许范围内时抛出
     */
    int publishDueAlerts(Instant now, int batchSize);
}
