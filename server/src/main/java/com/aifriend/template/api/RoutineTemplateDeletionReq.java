package com.aifriend.template.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 日常指令模板全量清除请求。
 *
 * @param confirmed 必须为 true，表示用户已完成第二次明确确认
 * @param expectedVersion 可选的 owner 日常模板命名空间版本
 * @author Codex
 * @since 1.0.0
 */
public record RoutineTemplateDeletionReq(
        @NotNull Boolean confirmed,
        @Positive Long expectedVersion) {
}
