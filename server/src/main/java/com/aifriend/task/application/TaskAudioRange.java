package com.aifriend.task.application;

/**
 * 音频有效内容半开区间。
 *
 * @param startMs 起始毫秒，包含
 * @param endMs 结束毫秒，不包含
 * @author Codex
 * @since 1.0.0
 */
public record TaskAudioRange(int startMs, int endMs) {
}
