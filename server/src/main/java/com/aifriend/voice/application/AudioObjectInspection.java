package com.aifriend.voice.application;

/**
 * 音频解码后的最小可信检查结果。
 *
 * @param durationMs 根据实际解码帧数计算的时长，单位毫秒
 * @author Codex
 * @since 1.0.0
 */
public record AudioObjectInspection(int durationMs) {
}
