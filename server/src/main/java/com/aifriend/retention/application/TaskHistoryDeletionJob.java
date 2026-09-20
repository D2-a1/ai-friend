package com.aifriend.retention.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 内部任务历史清除作业快照。
 *
 * @param id 作业 UUID
 * @param ownerUserId owner UUID
 * @param cutoffAt 受理截止时间
 * @param retryCount 已失败重试次数
 * @author Codex
 * @since 1.0.0
 */
public record TaskHistoryDeletionJob(UUID id, UUID ownerUserId, Instant cutoffAt, int retryCount) {
}