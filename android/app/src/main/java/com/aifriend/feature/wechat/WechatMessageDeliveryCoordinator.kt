package com.aifriend.feature.wechat

import com.aifriend.contract.model.ChannelPartResult
import com.aifriend.contract.model.ChannelResult
import com.aifriend.core.audio.CapturedAudio
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/** Open SDK 或后续受限适配器可返回的有限交付结果；不能表达真正发送成功。 */
enum class WechatMessageHandoffOutcome {
    HANDED_TO_WECHAT,
    UNSUPPORTED,
    FAILED,
}

/**
 * 只负责把内容交给当前计划批准的微信交付流程，不返回 SENT。
 *
 * 实现不得记录音频、文字或目标信息；异常必须由编排器收口为有限失败结果。
 */
interface WechatMessageHandoffPort {
    suspend fun handoffAudio(audio: CapturedAudio): WechatMessageHandoffOutcome

    suspend fun handoffText(text: String): WechatMessageHandoffOutcome
}

/** 当前一次“原声优先、文字补充”交付结果。 */
data class WechatMessageDeliveryReport(
    val result: ChannelResult,
    val parts: List<ChannelPartResult>,
) {
    init {
        require(parts.isNotEmpty()) { "微信交付结果不能为空" }
        require(
            result in setOf(
                ChannelResult.HANDED_TO_WECHAT,
                ChannelResult.PARTIAL,
                ChannelResult.UNSUPPORTED,
                ChannelResult.FAILED,
            ),
        ) { "消息交付结果超出允许范围" }
        require(
            parts.none { part ->
                part.result == ChannelPartResult.Result.SENT ||
                    part.result == ChannelPartResult.Result.CALL_STARTED
            },
        ) { "交给微信不能冒充发送或通话成功" }
    }
}

/**
 * 当前动作计划无法创建精确校准消息端口时的失败关闭结果。
 *
 * 只报告原声未交付；文字保持未尝试，避免退化为未精确选择联系人的普通分享。
 */
internal fun unsupportedWechatMessageDeliveryReport(
    evidenceCode: String = "CALIBRATED_MESSAGE_SHARE_UNAVAILABLE",
): WechatMessageDeliveryReport =
    WechatMessageDeliveryReport(
        result = ChannelResult.UNSUPPORTED,
        parts = listOf(
            ChannelPartResult(
                part = ChannelPartResult.Part.AUDIO,
                result = ChannelPartResult.Result.UNSUPPORTED,
                evidenceCode = evidenceCode,
            ),
        ),
    )

/**
 * 消息两段交付纯编排器。
 *
 * 原声未交给微信时绝不继续文字；原声成功而文字失败时只报告部分完成，绝不重发原声。
 * 本类不清除调用方持有的音频，音频生命周期仍由当前任务页面统一负责。
 */
class WechatMessageDeliveryCoordinator @Inject constructor() {
    suspend fun deliver(
        audio: CapturedAudio,
        messageText: String,
        port: WechatMessageHandoffPort,
    ): WechatMessageDeliveryReport {
        require(audio.wavBytes.isNotEmpty()) { "消息原声不能为空" }
        require(messageText.isNotBlank()) { "消息文字不能为空" }

        val audioOutcome = handoff { port.handoffAudio(audio) }
        val audioPart = audioOutcome.toPart(ChannelPartResult.Part.AUDIO)
        if (audioOutcome != WechatMessageHandoffOutcome.HANDED_TO_WECHAT) {
            return WechatMessageDeliveryReport(
                result = audioOutcome.toTerminalChannelResult(),
                parts = listOf(audioPart),
            )
        }

        val textOutcome = handoff {
            port.handoffText(REFERENCE_TEXT_PREFIX + messageText)
        }
        val textPart = textOutcome.toPart(ChannelPartResult.Part.TEXT)
        return WechatMessageDeliveryReport(
            result = if (textOutcome == WechatMessageHandoffOutcome.HANDED_TO_WECHAT) {
                ChannelResult.HANDED_TO_WECHAT
            } else {
                ChannelResult.PARTIAL
            },
            parts = listOf(audioPart, textPart),
        )
    }

    private suspend fun handoff(
        operation: suspend () -> WechatMessageHandoffOutcome,
    ): WechatMessageHandoffOutcome = try {
        operation()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        WechatMessageHandoffOutcome.FAILED
    }

    private fun WechatMessageHandoffOutcome.toPart(
        part: ChannelPartResult.Part,
    ): ChannelPartResult = ChannelPartResult(
        part = part,
        result = when (this) {
            WechatMessageHandoffOutcome.HANDED_TO_WECHAT ->
                ChannelPartResult.Result.HANDED_TO_WECHAT
            WechatMessageHandoffOutcome.UNSUPPORTED -> ChannelPartResult.Result.UNSUPPORTED
            WechatMessageHandoffOutcome.FAILED -> ChannelPartResult.Result.FAILED
        },
    )

    private fun WechatMessageHandoffOutcome.toTerminalChannelResult(): ChannelResult = when (this) {
        WechatMessageHandoffOutcome.HANDED_TO_WECHAT -> ChannelResult.HANDED_TO_WECHAT
        WechatMessageHandoffOutcome.UNSUPPORTED -> ChannelResult.UNSUPPORTED
        WechatMessageHandoffOutcome.FAILED -> ChannelResult.FAILED
    }

    private companion object {
        const val REFERENCE_TEXT_PREFIX = "AI好友转写，仅供参考："
    }
}
