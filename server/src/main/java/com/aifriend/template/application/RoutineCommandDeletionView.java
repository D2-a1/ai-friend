package com.aifriend.template.application;

import java.time.Instant;

/**
 * 日常指令模板清除用例结果。
 *
 * @param deletedCount 首次请求实际删除数量
 * @param deletedAt 服务端完成删除的 UTC 时间
 * @author Codex
 * @since 1.0.0
 */
public record RoutineCommandDeletionView(
        int deletedCount,
        Instant deletedAt) {
}
