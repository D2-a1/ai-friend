package com.aifriend.personalization.domain;

/**
 * 助手对话详略偏好。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum DialogueStylePreference {
    /** 只播报完成当前任务所需的最少信息。 */
    BRIEF,
    /** 播报标准解释和纠错提示。 */
    STANDARD
}
