package com.aifriend.core.nlu

/**
 * 关键词产生的意图信号类型。
 */
enum class IntentSignalType {
    CANCELLATION,
    CORRECTION,
    VIDEO_CALL,
    VOICE_CALL,
    SEND_MESSAGE,
}

/**
 * 本地关键词意图信号。
 *
 * @property type 信号类型
 * @property keyword 命中的规范关键词
 * @property priority 安全优先级，数值越小越优先
 */
data class IntentSignal(
    val type: IntentSignalType,
    val keyword: String,
    val priority: Int,
) {
    init {
        require(keyword.isNotBlank()) { "keyword must not be blank" }
        require(priority >= 0) { "priority must not be negative" }
    }
}

/**
 * 本地安全优先关键词匹配器。
 *
 * 只生成候选信号，不直接执行消息或通话；取消和纠正优先于动作信号。
 */
class KeywordIntentMatcher {
    /** 从规范化文本中提取按安全优先级排序的信号。 */
    fun match(normalizedText: String?): List<IntentSignal> {
        if (normalizedText.isNullOrBlank()) return emptyList()
        val comparableText = normalizedText.trim().lowercase()
        return KEYWORDS.mapNotNull { (type, keywords) ->
            keywords.firstOrNull(comparableText::contains)?.let { keyword ->
                IntentSignal(type, keyword, PRIORITIES.getValue(type))
            }
        }.sortedBy(IntentSignal::priority)
    }

    private companion object {
        val KEYWORDS = mapOf(
            IntentSignalType.CANCELLATION to listOf("取消", "不发了", "不要打了"),
            IntentSignalType.CORRECTION to listOf("不对", "说错了", "改成"),
            IntentSignalType.VIDEO_CALL to listOf("打视频", "视频通话"),
            IntentSignalType.VOICE_CALL to listOf("打电话", "打语音", "语音通话"),
            IntentSignalType.SEND_MESSAGE to listOf("发消息", "发信息", "说给"),
        )

        val PRIORITIES = mapOf(
            IntentSignalType.CANCELLATION to 0,
            IntentSignalType.CORRECTION to 1,
            IntentSignalType.VIDEO_CALL to 10,
            IntentSignalType.VOICE_CALL to 10,
            IntentSignalType.SEND_MESSAGE to 10,
        )
    }
}
