package com.aifriend.template.application;

import java.util.UUID;

/**
 * 与新候选满足同短语合并阈值的既有模板。
 *
 * @param templateId 既有模板 UUID
 * @param templateVersion 既有模板并发版本
 * @param normalizedDistance 归一化 DTW 距离
 * @author Codex
 * @since 1.0.0
 */
public record RoutineCommandTemplateMatch(
        UUID templateId,
        long templateVersion,
        double normalizedDistance) {
}
