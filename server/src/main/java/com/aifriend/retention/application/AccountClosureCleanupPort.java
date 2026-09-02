package com.aifriend.retention.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 跨 MySQL 与私有音频对象存储的账号注销清理端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface AccountClosureCleanupPort {

    /**
     * 有界清除一批账号数据，外部对象删除不得位于数据库事务中。
     *
     * @param ownerUserId owner UUID
     * @param batchSize 单次最多处理的主记录数
     * @return 本次处理的主记录数
     * @throws IllegalArgumentException 当批次大小不在允许范围内时抛出
     * @throws RuntimeException 当对象存储或数据库清理失败时抛出
     */
    int cleanupBatch(UUID ownerUserId, int batchSize);

    /**
     * 锁内逐表复验，并在全部在线数据清零时完成注销和清空可解密身份材料。
     *
     * @param jobId 注销作业 UUID
     * @param ownerUserId owner UUID
     * @param now 当前 UTC 时间
     * @return 本次是否从 ACCEPTED 进入 COMPLETED
     * @throws RuntimeException 当锁内复验或完成状态写入失败时抛出
     */
    boolean finalizeIfCleared(UUID jobId, UUID ownerUserId, Instant now);
}
