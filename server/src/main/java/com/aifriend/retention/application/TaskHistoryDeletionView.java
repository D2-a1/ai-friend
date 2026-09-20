package com.aifriend.retention.application;

import java.time.Instant;

/**
 * 对外任务历史清除状态。
 *
 * @param status 仅允许 CLEARING 或 COMPLETED
 * @param requestedAt 可靠受理时间
 * @param completedAt 全部目标存储复验完成时间
 * @author Codex
 * @since 1.0.0
 */
public record TaskHistoryDeletionView(String status, Instant requestedAt, Instant completedAt) {
}