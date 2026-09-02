package com.aifriend.feature.wechat

import com.aifriend.contract.model.ChannelPartResult
import com.aifriend.contract.model.ChannelResult
import com.aifriend.contract.model.WechatActionType
import java.security.MessageDigest
import java.time.Duration
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 最终通话类型点击后，只在当前进程短时等待签名“通话中”页面。 */
data class WechatCallStartedTransitionRequest(
    val planId: String,
    val action: WechatActionType,
    val expectedPageType: WechatPageType,
    val capability: WechatCapabilitySnapshot,
    val openedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
)

enum class WechatCallStartedTransitionStatus {
    CONFIRMED,
    NOT_CONFIRMED,
    SERVICE_UNAVAILABLE,
}

data class WechatCallStartedTransitionOutcome(
    val planId: String,
    val action: WechatActionType,
    val status: WechatCallStartedTransitionStatus,
)

/**
 * 只有签名通话中页面才报告 CALL_STARTED；其余情况保留已接受点击这一可信事实。
 * 不把麦克风占用、计时经过或辅助服务中断推断成通话已经开始。
 */
internal fun WechatCallStartedTransitionOutcome.toDeliveryReport():
    WechatCallChoiceDeliveryReport {
    val confirmed = status == WechatCallStartedTransitionStatus.CONFIRMED
    val actionCode = when (action) {
        WechatActionType.START_VOICE_CALL -> "VOICE"
        WechatActionType.START_VIDEO_CALL -> "VIDEO"
        WechatActionType.SEND_AUDIO_AND_TEXT -> error("消息动作不能生成通话开始结果")
    }
    return WechatCallChoiceDeliveryReport(
        result = if (confirmed) ChannelResult.CALL_STARTED else ChannelResult.OPENED,
        parts = listOf(
            ChannelPartResult(
                part = ChannelPartResult.Part.CALL,
                result = if (confirmed) {
                    ChannelPartResult.Result.CALL_STARTED
                } else {
                    ChannelPartResult.Result.HANDED_TO_WECHAT
                },
                evidenceCode = if (confirmed) {
                    "${actionCode}_CALL_ACTIVE_PAGE_CONFIRMED"
                } else {
                    "${actionCode}_CALL_ACTIVE_PAGE_${status.name}"
                },
            ),
        ),
    )
}

@Singleton
class WechatCallStartedTransitionBroker @Inject constructor() {
    private val mutableOutcomes = MutableSharedFlow<WechatCallStartedTransitionOutcome>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var request: WechatCallStartedTransitionRequest? = null

    val outcomes: SharedFlow<WechatCallStartedTransitionOutcome> = mutableOutcomes.asSharedFlow()

    /** 没有逐页签名时返回 false，由调用方立即按已交给微信收口。 */
    @Synchronized
    fun arm(source: WechatCallChoiceActionRequest, now: OffsetDateTime): Boolean {
        request = null
        val pageType = source.action.activeCallPageType()
        if (pageType !in source.capability.allowedPageTypes[source.action].orEmpty() ||
            source.capability.pageSignatures(source.action, pageType).isEmpty()
        ) {
            return false
        }
        request = WechatCallStartedTransitionRequest(
            planId = source.planId,
            action = source.action,
            expectedPageType = pageType,
            capability = source.capability,
            openedAt = now,
            expiresAt = now.plus(TRANSITION_WINDOW),
        )
        return true
    }

    fun activeRequest(
        packageName: String,
        now: OffsetDateTime,
    ): WechatCallStartedTransitionRequest? {
        var expired: WechatCallStartedTransitionRequest? = null
        val active = synchronized(this) {
            val current = request ?: return@synchronized null
            if (now.isBefore(current.openedAt) || !current.expiresAt.isAfter(now)) {
                request = null
                expired = current
                return@synchronized null
            }
            current.takeIf { packageName == current.capability.packageName }
        }
        expired?.let { publish(it, WechatCallStartedTransitionStatus.NOT_CONFIRMED) }
        return active
    }

    fun observe(
        planId: String,
        packageName: String,
        sample: WechatPageStructureSample,
        now: OffsetDateTime,
    ): Boolean {
        val confirmed = synchronized(this) {
            val current = request ?: return@synchronized null
            if (current.planId != planId || packageName != current.capability.packageName ||
                now.isBefore(current.openedAt) || !current.expiresAt.isAfter(now) ||
                sample.capturedAt.isBefore(current.openedAt) || sample.capturedAt.isAfter(now)
            ) {
                return@synchronized null
            }
            val allowed = current.capability.pageSignatures(
                current.action,
                current.expectedPageType,
            ).any { expected -> constantTimeEquals(expected, sample.signatureSha256) }
            if (!allowed) return@synchronized null
            request = null
            current
        } ?: return false
        publish(confirmed, WechatCallStartedTransitionStatus.CONFIRMED)
        return true
    }

    fun expire(planId: String, now: OffsetDateTime) {
        val expired = synchronized(this) {
            val current = request ?: return@synchronized null
            if (current.planId != planId || current.expiresAt.isAfter(now)) {
                return@synchronized null
            }
            request = null
            current
        } ?: return
        publish(expired, WechatCallStartedTransitionStatus.NOT_CONFIRMED)
    }

    fun interrupt() {
        val interrupted = synchronized(this) {
            val current = request
            request = null
            current
        } ?: return
        publish(interrupted, WechatCallStartedTransitionStatus.SERVICE_UNAVAILABLE)
    }

    @Synchronized
    fun clear() {
        request = null
    }

    private fun publish(
        source: WechatCallStartedTransitionRequest,
        status: WechatCallStartedTransitionStatus,
    ) {
        mutableOutcomes.tryEmit(
            WechatCallStartedTransitionOutcome(source.planId, source.action, status),
        )
    }

    private fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.encodeToByteArray(), right.encodeToByteArray())

    private companion object {
        val TRANSITION_WINDOW: Duration = Duration.ofSeconds(3)
    }
}

internal fun WechatActionType.activeCallPageType(): WechatPageType = when (this) {
    WechatActionType.START_VOICE_CALL -> WechatPageType.VOICE_CALL_ACTIVE
    WechatActionType.START_VIDEO_CALL -> WechatPageType.VIDEO_CALL_ACTIVE
    WechatActionType.SEND_AUDIO_AND_TEXT -> error("消息动作没有通话中页面")
}
