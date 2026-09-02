package com.aifriend.feature.task

import com.aifriend.contract.model.ChannelPartResult
import com.aifriend.contract.model.ChannelResult
import com.aifriend.contract.model.Intent
import com.aifriend.contract.model.TaskSession
import com.aifriend.contract.model.TaskState

/** 五秒消息继续窗口的公开 UI 快照，不暴露联系人内部编号。 */
data class MessageContinuationUi(
    val contactLabel: String,
    val secondsRemaining: Int,
)

/**
 * 只驻留当前进程的消息继续窗口。
 *
 * 只接受服务端返回的消息终态及完整渠道证据；到期或消费后立即清除联系人编号。
 */
internal class MessageContinuationWindow(
    private val monotonicNowMillis: () -> Long,
) {
    private var context: Context? = null

    fun open(session: TaskSession): MessageContinuationUi? {
        val contact = session.understanding?.contact ?: return clearAndReturnNull()
        if (!hasTrustedMessageResult(session)) return clearAndReturnNull()
        val label = contact.displayName.ifBlank { contact.alias }
        if (contact.id.isBlank() || label.isBlank()) return clearAndReturnNull()
        context = Context(
            contactId = contact.id,
            contactLabel = label,
            expiresAtMillis = monotonicNowMillis() + WINDOW_MILLIS,
        )
        return snapshot()
    }

    fun snapshot(): MessageContinuationUi? {
        val current = context ?: return null
        val remainingMillis = current.expiresAtMillis - monotonicNowMillis()
        if (remainingMillis <= 0L) return clearAndReturnNull()
        return MessageContinuationUi(
            contactLabel = current.contactLabel,
            secondsRemaining = ((remainingMillis + 999L) / 1_000L).toInt(),
        )
    }

    fun consume(): String? {
        if (snapshot() == null) return null
        val contactId = context?.contactId
        clear()
        return contactId
    }

    fun clear() {
        context = null
    }

    private fun clearAndReturnNull(): MessageContinuationUi? {
        clear()
        return null
    }

    private fun hasTrustedMessageResult(session: TaskSession): Boolean {
        if (session.understanding?.intent != Intent.SEND_MESSAGE) return false
        val channelResult = session.channelResult ?: return false
        val audioSent = channelResult.parts.any { part ->
            part.part == ChannelPartResult.Part.AUDIO &&
                part.result == ChannelPartResult.Result.SENT &&
                !part.evidenceCode.isNullOrBlank()
        }
        return when {
            session.state == TaskState.COMPLETED &&
                channelResult.result == ChannelResult.SENT -> {
                audioSent && channelResult.parts.any { part ->
                    part.part == ChannelPartResult.Part.TEXT &&
                        part.result == ChannelPartResult.Result.SENT &&
                        !part.evidenceCode.isNullOrBlank()
                }
            }
            session.state == TaskState.PARTIAL &&
                channelResult.result == ChannelResult.PARTIAL -> {
                audioSent && channelResult.parts.any { part ->
                    part.part == ChannelPartResult.Part.TEXT &&
                        part.result == ChannelPartResult.Result.FAILED
                }
            }
            else -> false
        }
    }

    private data class Context(
        val contactId: String,
        val contactLabel: String,
        val expiresAtMillis: Long,
    )

    private companion object {
        const val WINDOW_MILLIS = 5_000L
    }
}
