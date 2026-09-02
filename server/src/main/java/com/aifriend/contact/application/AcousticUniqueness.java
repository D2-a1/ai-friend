package com.aifriend.contact.application;

/**
 * owner 全局称呼发音唯一性分类。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum AcousticUniqueness {
    /** 与现有称呼具有可靠区分度。 */
    DISTINCT,
    /** 与现有称呼相同或高度相似。 */
    CONFLICT,
    /** 位于阈值边界，必须重新录制或更换称呼。 */
    BORDERLINE
}
