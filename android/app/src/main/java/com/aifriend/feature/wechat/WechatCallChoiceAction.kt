package com.aifriend.feature.wechat

import android.view.accessibility.AccessibilityNodeInfo
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

/** 已签名通话选择页上允许消费一次的精确类型点击请求。 */
data class WechatCallChoiceActionRequest(
    val planId: String,
    val action: WechatActionType,
    val expectedPageType: WechatPageType,
    val capability: WechatCapabilitySnapshot,
    val openedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
) {
    init {
        require(action.isCallAction()) { "只允许微信语音或视频通话动作" }
        require(expectedPageType == action.transitionPageType()) { "通话页面类型不匹配" }
    }

    override fun toString(): String =
        "WechatCallChoiceActionRequest(planId=<redacted>, action=$action, " +
            "expectedPageType=$expectedPageType, capability=$capability, " +
            "openedAt=$openedAt, expiresAt=$expiresAt)"
}

enum class WechatCallChoiceActionStatus {
    CLICK_REQUEST_ACCEPTED,
    SERVICE_UNAVAILABLE,
    ROOT_UNAVAILABLE,
    PAGE_REVALIDATION_FAILED,
    ACTION_NOT_UNIQUE,
    ACTION_NOT_CLICKABLE,
    CLICK_REQUEST_REJECTED,
    AUDIO_RELEASE_FAILED,
}

data class WechatCallChoiceActionOutcome(
    val planId: String,
    val action: WechatActionType,
    val status: WechatCallChoiceActionStatus,
    val callStartedConfirmationArmed: Boolean = false,
)

/** 通话动作的有限结果；CALL_STARTED 仍不表达对方已接听或收到。 */
data class WechatCallChoiceDeliveryReport(
    val result: ChannelResult,
    val parts: List<ChannelPartResult>,
) {
    init {
        require(
            result == ChannelResult.OPENED || result == ChannelResult.CALL_STARTED ||
                result == ChannelResult.FAILED,
        )
        require(parts.size == 1 && parts.single().part == ChannelPartResult.Part.CALL)
        require(
            (result == ChannelResult.CALL_STARTED) ==
                (parts.single().result == ChannelPartResult.Result.CALL_STARTED),
        )
    }
}

internal fun WechatCallChoiceActionOutcome.toDeliveryReport(): WechatCallChoiceDeliveryReport {
    val accepted = status == WechatCallChoiceActionStatus.CLICK_REQUEST_ACCEPTED
    val actionCode = when (action) {
        WechatActionType.START_VOICE_CALL -> "VOICE"
        WechatActionType.START_VIDEO_CALL -> "VIDEO"
        WechatActionType.SEND_AUDIO_AND_TEXT -> error("消息动作不能生成通话结果")
    }
    return WechatCallChoiceDeliveryReport(
        result = if (accepted) ChannelResult.OPENED else ChannelResult.FAILED,
        parts = listOf(
            ChannelPartResult(
                part = ChannelPartResult.Part.CALL,
                result = if (accepted) {
                    ChannelPartResult.Result.HANDED_TO_WECHAT
                } else {
                    ChannelPartResult.Result.FAILED
                },
                evidenceCode = if (accepted) {
                    "${actionCode}_CALL_CLICK_REQUEST_ACCEPTED"
                } else {
                    "${actionCode}_CALL_${status.name}"
                },
            ),
        ),
    )
}

internal fun failedWechatCallDeliveryReport(
    action: WechatActionType,
    evidenceCode: String,
): WechatCallChoiceDeliveryReport {
    require(action.isCallAction()) { "消息动作不能生成通话失败结果" }
    require(evidenceCode.isNotBlank() && evidenceCode.length <= 80) { "通话失败证据码无效" }
    return WechatCallChoiceDeliveryReport(
        result = ChannelResult.FAILED,
        parts = listOf(
            ChannelPartResult(
                part = ChannelPartResult.Part.CALL,
                result = ChannelPartResult.Result.FAILED,
                evidenceCode = evidenceCode,
            ),
        ),
    )
}

/** 页面确认后建立的两秒一次性通话类型请求；无订阅者时立即失败关闭。 */
@Singleton
class WechatCallChoiceActionBroker @Inject constructor() {
    private val mutableRequests = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val mutableOutcomes = MutableSharedFlow<WechatCallChoiceActionOutcome>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var request: WechatCallChoiceActionRequest? = null

    val requests: SharedFlow<String> = mutableRequests.asSharedFlow()
    val outcomes: SharedFlow<WechatCallChoiceActionOutcome> = mutableOutcomes.asSharedFlow()

    fun arm(source: WechatDirectChatTransitionRequest, now: OffsetDateTime) {
        val armed = synchronized(this) {
            request = null
            val expiresAt = listOf(now.plus(ACTION_WINDOW), source.expiresAt)
                .minBy { it.toInstant() }
            if (!source.action.isCallAction() || !expiresAt.isAfter(now) ||
                source.expectedPageType != source.action.transitionPageType() ||
                source.capability.pageSignatures(
                    source.action,
                    source.expectedPageType,
                ).isEmpty()
            ) {
                false
            } else {
                request = WechatCallChoiceActionRequest(
                    planId = source.planId,
                    action = source.action,
                    expectedPageType = source.expectedPageType,
                    capability = source.capability,
                    openedAt = now,
                    expiresAt = expiresAt,
                )
                true
            }
        }
        if (!armed) return
        if (mutableRequests.subscriptionCount.value == 0) {
            val outcome = synchronized(this) {
                val current = request ?: return@synchronized null
                request = null
                WechatCallChoiceActionOutcome(
                    current.planId,
                    current.action,
                    WechatCallChoiceActionStatus.SERVICE_UNAVAILABLE,
                )
            }
            outcome?.let(::publish)
            return
        }
        mutableRequests.tryEmit(source.planId)
    }

    @Synchronized
    fun take(
        planId: String,
        packageName: String,
        now: OffsetDateTime,
    ): WechatCallChoiceActionRequest? {
        val current = request ?: return null
        if (now.isBefore(current.openedAt) || !current.expiresAt.isAfter(now)) {
            request = null
            return null
        }
        if (current.planId != planId || current.capability.packageName != packageName) return null
        request = null
        return current
    }

    fun publish(outcome: WechatCallChoiceActionOutcome) {
        mutableOutcomes.tryEmit(outcome)
    }

    fun interrupt() {
        val outcome = synchronized(this) {
            val current = request ?: return@synchronized null
            request = null
            WechatCallChoiceActionOutcome(
                current.planId,
                current.action,
                WechatCallChoiceActionStatus.SERVICE_UNAVAILABLE,
            )
        }
        outcome?.let(::publish)
    }

    @Synchronized
    fun clear() {
        request = null
    }

    private companion object {
        val ACTION_WINDOW: Duration = Duration.ofSeconds(2)
    }
}

internal object WechatCallChoiceActionPolicy {
    fun accepts(
        request: WechatCallChoiceActionRequest,
        sample: WechatPageStructureSample,
        now: OffsetDateTime,
    ): Boolean {
        if (now.isBefore(request.openedAt) || !request.expiresAt.isAfter(now) ||
            sample.capturedAt.isBefore(request.openedAt) || sample.capturedAt.isAfter(now)
        ) {
            return false
        }
        return request.capability.pageSignatures(request.action, request.expectedPageType)
            .any { allowed -> constantTimeEquals(allowed, sample.signatureSha256) }
    }

    private fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.encodeToByteArray(), right.encodeToByteArray())
}

/** 已复验通话选择页后，只点击唯一、可见、可用的精确通话类型节点一次。 */
@Singleton
class WechatCallChoiceActionExecutor @Inject constructor(
    private val pageReader: WechatAccessibilityPageReader,
) {
    fun execute(
        root: AccessibilityNodeInfo?,
        request: WechatCallChoiceActionRequest,
        now: OffsetDateTime,
    ): WechatCallChoiceActionOutcome {
        val rootNode = root ?: return outcome(request, WechatCallChoiceActionStatus.ROOT_UNAVAILABLE)
        if (rootNode.packageName?.toString() != request.capability.packageName) {
            return outcome(request, WechatCallChoiceActionStatus.PAGE_REVALIDATION_FAILED)
        }
        val sample = pageReader.readStructureOnlySample(rootNode, now)
            ?: return outcome(request, WechatCallChoiceActionStatus.PAGE_REVALIDATION_FAILED)
        if (!WechatCallChoiceActionPolicy.accepts(request, sample, now)) {
            return outcome(request, WechatCallChoiceActionStatus.PAGE_REVALIDATION_FAILED)
        }
        val actionQuery = WechatCallChoiceTextRule.query(request.action)
        val nodes = runCatching { rootNode.findAccessibilityNodeInfosByText(actionQuery) }
            .getOrNull()
            ?: return outcome(request, WechatCallChoiceActionStatus.ACTION_NOT_UNIQUE)
        return try {
            val exact = nodes.filter { node ->
                WechatCallChoiceTextRule.matches(node.text, request.action)
            }
            when {
                exact.size != 1 -> outcome(request, WechatCallChoiceActionStatus.ACTION_NOT_UNIQUE)
                !exact.single().isClickable || !exact.single().isEnabled ||
                    !exact.single().isVisibleToUser -> outcome(
                    request,
                    WechatCallChoiceActionStatus.ACTION_NOT_CLICKABLE,
                )
                runCatching {
                    exact.single().performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false) -> outcome(
                    request,
                    WechatCallChoiceActionStatus.CLICK_REQUEST_ACCEPTED,
                )
                else -> outcome(request, WechatCallChoiceActionStatus.CLICK_REQUEST_REJECTED)
            }
        } finally {
            nodes.forEach { node -> node.recycleOwned() }
        }
    }

    private fun outcome(
        request: WechatCallChoiceActionRequest,
        status: WechatCallChoiceActionStatus,
    ): WechatCallChoiceActionOutcome =
        WechatCallChoiceActionOutcome(request.planId, request.action, status)

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.recycleOwned() {
        if (android.os.Build.VERSION.SDK_INT < 33) recycle()
    }
}

internal object WechatCallChoiceTextRule {
    fun query(action: WechatActionType): String = when (action) {
        WechatActionType.START_VOICE_CALL -> "语音通话"
        WechatActionType.START_VIDEO_CALL -> "视频通话"
        WechatActionType.SEND_AUDIO_AND_TEXT -> error("消息动作没有通话类型")
    }

    fun matches(text: CharSequence?, action: WechatActionType): Boolean {
        val expected = query(action)
        if (text == null || text.length > MAXIMUM_TEXT_LENGTH) return false
        var start = 0
        while (start < text.length && text[start].isWhitespace()) start++
        var end = text.length
        while (end > start && text[end - 1].isWhitespace()) end--
        if (end - start != expected.length) return false
        return expected.indices.all { index -> text[start + index] == expected[index] }
    }

    private const val MAXIMUM_TEXT_LENGTH = 16
}

internal fun WechatActionType.isCallAction(): Boolean =
    this == WechatActionType.START_VOICE_CALL || this == WechatActionType.START_VIDEO_CALL
