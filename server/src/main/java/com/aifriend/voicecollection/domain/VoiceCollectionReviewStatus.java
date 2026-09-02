package com.aifriend.voicecollection.domain;

/**
 * 测试语音样本人工复核状态。
 *
 * <p>PENDING 表示没有可核验的人工文字；CONFIRMED 表示用户已在本机试听并确认文字。
 * 该状态不是训练完成或模型发布状态。</p>
 *
 * @author codex
 * @since 1.0.0
 */
public enum VoiceCollectionReviewStatus {
    /** 尚未完成人工复核。 */
    PENDING,
    /** 已在本机试听并人工确认文字。 */
    CONFIRMED
}
