package com.aifriend.core.feedback

/** 不依赖颜色的可见状态符号。 */
enum class FeedbackSymbol(val glyph: String) {
    OFF("—"),
    STARTING("▶"),
    WAITING("…"),
    LISTENING("●"),
    PROCESSING("…"),
    ATTENTION("?"),
    BUSY("Ⅱ"),
    SUCCESS("✓"),
    CANCELLED("×"),
    ERROR("!"),
}

/** 状态徽章的有限视觉语义。 */
enum class FeedbackTone {
    NEUTRAL,
    ACTIVE,
    ATTENTION,
    SUCCESS,
    ERROR,
}

/** 不包含业务内容的有限震动提示。 */
enum class HapticCue {
    NONE,
    LISTENING,
    ATTENTION,
    BUSY,
    SUCCESS,
    CANCELLED,
    ERROR,
}

/** 页面状态的可见、可读和震动提示。 */
data class AccessibleFeedbackPresentation(
    val symbol: FeedbackSymbol,
    val tone: FeedbackTone,
    val label: String,
    val accessibilityLabel: String,
    val hapticCue: HapticCue,
    val urgent: Boolean = false,
)

/** 本机震动端口；不持久化、不包含消息或联系人。 */
interface HapticFeedbackPort {
    fun emit(cue: HapticCue)
    fun cancel()
}

/**
 * 为每个有限提示返回不重复、不循环的短震动时序。
 *
 * @author codex
 * @since 2026-08-22
 */
object HapticPatternRegistry {
    private val patterns = mapOf(
        HapticCue.LISTENING to longArrayOf(0L, 60L),
        HapticCue.ATTENTION to longArrayOf(0L, 70L, 80L, 70L),
        HapticCue.BUSY to longArrayOf(0L, 80L, 100L, 80L),
        HapticCue.SUCCESS to longArrayOf(0L, 60L, 60L, 100L),
        HapticCue.CANCELLED to longArrayOf(0L, 120L),
        HapticCue.ERROR to longArrayOf(0L, 100L, 80L, 100L, 80L, 180L),
    )

    fun pattern(cue: HapticCue): LongArray? = patterns[cue]?.copyOf()
}
