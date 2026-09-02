package com.aifriend.template.api;

import java.time.Instant;

/**
 * 日常指令模板全量清除响应。
 *
 * @param deletedCount 本次首次请求实际删除的模板数量，范围为 0 至 30
 * @param deletedAt 服务端完成删除的 UTC 时间
 * @author Codex
 * @since 1.0.0
 */
public record RoutineTemplateDeletionResp(
        int deletedCount,
        Instant deletedAt) {
}
