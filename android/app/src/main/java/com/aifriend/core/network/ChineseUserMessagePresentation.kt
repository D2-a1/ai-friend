package com.aifriend.core.network

/**
 * 将领域异常中已经收敛的中文说明交给界面，未知技术异常使用固定中文回落。
 *
 * 英文字母、换行、控制字符或过长内容通常来自 SDK、网络库或程序错误，
 * 不得直接显示给老人。
 */
fun Throwable.toChineseUserMessage(fallback: String): String {
    val safeFallback = fallback.trim().takeIf(::isBoundedChineseMessage)
        ?: DEFAULT_FAILURE_MESSAGE
    return message?.trim()?.takeIf(::isBoundedChineseMessage) ?: safeFallback
}

private fun isBoundedChineseMessage(message: String): Boolean =
    message.isNotEmpty() &&
        message.length <= MAXIMUM_MESSAGE_LENGTH &&
        message.any { character ->
            character in '\u3400'..'\u4DBF' || character in '\u4E00'..'\u9FFF'
        } &&
        message.none { character ->
            character in 'A'..'Z' ||
                character in 'a'..'z' ||
                character == '\n' ||
                character == '\r' ||
                character.isISOControl()
        }

private const val MAXIMUM_MESSAGE_LENGTH = 160
private const val DEFAULT_FAILURE_MESSAGE = "操作没有完成，请稍后再试"
