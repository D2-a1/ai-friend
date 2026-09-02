package com.aifriend.retention.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 跨任务表与 TASK 音频存储的专用清除端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface TaskHistoryStoragePort {
    /**
     * 清除一批受理截止时间之前的数据。
     *
     * @param ownerUserId owner UUID
     * @param cutoffAt 清除受理截止时间
     * @param batchSize 单次最大目标数量
     * @return 本次处理数量
     */
    int cleanupBatch(UUID ownerUserId, Instant cutoffAt, int batchSize);
    /**
     * 逐存储复验目标数据是否为零。
     *
     * @param ownerUserId owner UUID
     * @param cutoffAt 清除受理截止时间
     * @return 所有目标存储已无待清数据时返回 true
     */
    boolean isCleared(UUID ownerUserId, Instant cutoffAt);
}
