package com.aifriend.template.application;

import java.time.Instant;

/**
 * 已完成日常指令模板清除的最小幂等事实。
 *
 * @param requestHash 请求语义 SHA-256
 * @param deletedCount 首次请求实际删除数量
 * @param deletedAt 首次删除完成时间
 * @param namespaceVersionAfter 删除后的命名空间版本
 * @author Codex
 * @since 1.0.0
 */
public record RoutineCommandDeletionRecord(
        byte[] requestHash,
        int deletedCount,
        Instant deletedAt,
        long namespaceVersionAfter) {
}
