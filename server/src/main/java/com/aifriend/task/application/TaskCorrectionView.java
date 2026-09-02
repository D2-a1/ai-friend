package com.aifriend.task.application;

/**
 * 一次明确纠正的原声区间映射。
 *
 * @param slot CONTACT、ACTION 或 CONTENT
 * @param supersededRange 被替换区间
 * @param replacementRange 替换区间
 * @author Codex
 * @since 1.0.0
 */
public record TaskCorrectionView(
        String slot,
        TaskAudioRange supersededRange,
        TaskAudioRange replacementRange) {
}
