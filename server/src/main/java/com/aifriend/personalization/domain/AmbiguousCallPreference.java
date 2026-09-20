package com.aifriend.personalization.domain;

/**
 * 用户只说“联系”或“通话”而未明确语音/视频时的理解偏好。
 *
 * <p>该偏好只能帮助生成待复述草稿，不能跳过联系人匹配、复述或确认。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
public enum AmbiguousCallPreference {
    /** 每次追问语音还是视频。 */
    ASK_EVERY_TIME,
    /** 优先生成微信语音通话草稿。 */
    VOICE,
    /** 优先生成微信视频通话草稿。 */
    VIDEO
}
