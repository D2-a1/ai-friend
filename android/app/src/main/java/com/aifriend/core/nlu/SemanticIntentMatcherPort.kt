package com.aifriend.core.nlu

/**
 * 语义意图匹配扩展端口。
 *
 * 当前没有默认实现。未来本地模型或云端适配器只返回候选信号，
 * 不得绕过白名单、复述和动作型确认。
 */
fun interface SemanticIntentMatcherPort {
    /** 根据规范化文本生成语义候选信号。 */
    fun match(normalizedText: String): List<IntentSignal>
}
