package com.aifriend.feature.wechat

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

/** 点击联系人资料页后短时存在的聊天页确认请求，不包含节点文字、联系人或消息内容。 */
data class WechatDirectChatTransitionRequest(
    val planId: String,
    val action: WechatActionType,
    val expectedPageType: WechatPageType,
    val capability: WechatCapabilitySnapshot,
    val openedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
)

enum class WechatDirectChatTransitionStatus {
    CONFIRMED,
    NOT_CONFIRMED,
    SERVICE_UNAVAILABLE,
}

data class WechatDirectChatTransitionOutcome(
    val planId: String,
    val status: WechatDirectChatTransitionStatus,
)

/** 只允许紧随已复验资料页点击之后，以签名结构确认一次目标聊天页。 */
@Singleton
class WechatDirectChatTransitionBroker @Inject constructor() {
    private val mutableOutcomes = MutableSharedFlow<WechatDirectChatTransitionOutcome>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var request: WechatDirectChatTransitionRequest? = null
    private var confirmedRequest: WechatDirectChatTransitionRequest? = null

    val outcomes: SharedFlow<WechatDirectChatTransitionOutcome> = mutableOutcomes.asSharedFlow()

    fun arm(source: WechatVerifiedContactProfileActionRequest, now: OffsetDateTime): Boolean {
        val armed = synchronized(this) {
            request = null
            confirmedRequest = null
            val expiresAt = listOf(
                now.plus(TRANSITION_WINDOW),
                source.targetLocatorProof.expiresAt,
            ).minBy { it.toInstant() }
            val expectedPageType = source.action.transitionPageType()
            if (!expiresAt.isAfter(now) ||
                expectedPageType !in source.capability.allowedPageTypes[
                    source.action
                ].orEmpty() ||
                source.capability.pageSignatures(
                    source.action,
                    expectedPageType,
                ).isEmpty()
            ) {
                false
            } else {
                request = WechatDirectChatTransitionRequest(
                    source.planId,
                    source.action,
                    expectedPageType,
                    source.capability,
                    now,
                    expiresAt,
                )
                true
            }
        }
        if (!armed) publish(source.planId, WechatDirectChatTransitionStatus.NOT_CONFIRMED)
        return armed
    }

    fun activeRequest(
        packageName: String,
        now: OffsetDateTime,
    ): WechatDirectChatTransitionRequest? {
        var expiredPlanId: String? = null
        val active = synchronized(this) {
            val current = request ?: return@synchronized null
            if (now.isBefore(current.openedAt) || !current.expiresAt.isAfter(now)) {
                request = null
                expiredPlanId = current.planId
                return@synchronized null
            }
            current.takeIf { packageName == it.capability.packageName }
        }
        expiredPlanId?.let { planId ->
            publish(planId, WechatDirectChatTransitionStatus.NOT_CONFIRMED)
        }
        return active
    }

    fun observe(
        planId: String,
        packageName: String,
        sample: WechatPageStructureSample,
        now: OffsetDateTime,
    ): Boolean {
        val confirmed = synchronized(this) {
            val current = request ?: return@synchronized false
            if (current.planId != planId || packageName != current.capability.packageName ||
                sample.capturedAt.isBefore(current.openedAt) || sample.capturedAt.isAfter(now) ||
                !current.expiresAt.isAfter(now)
            ) {
                return@synchronized false
            }
            val allowed = current.capability.pageSignatures(
                current.action,
                current.expectedPageType,
            ).any { expected -> constantTimeEquals(expected, sample.signatureSha256) }
            if (allowed) {
                request = null
                confirmedRequest = current
            }
            allowed
        }
        if (confirmed) publish(planId, WechatDirectChatTransitionStatus.CONFIRMED)
        return confirmed
    }

    /** 页面确认后只允许辅助服务消费一次，用于紧接着建立通话类型点击请求。 */
    @Synchronized
    fun takeConfirmed(planId: String): WechatDirectChatTransitionRequest? {
        val current = confirmedRequest ?: return null
        if (current.planId != planId) return null
        confirmedRequest = null
        return current
    }

    fun expire(planId: String, now: OffsetDateTime) {
        val expired = synchronized(this) {
            val current = request ?: return@synchronized false
            if (current.planId != planId || current.expiresAt.isAfter(now)) return@synchronized false
            request = null
            true
        }
        if (expired) publish(planId, WechatDirectChatTransitionStatus.NOT_CONFIRMED)
    }

    fun interrupt() {
        val planId = synchronized(this) {
            val currentPlanId = request?.planId
            request = null
            currentPlanId
        } ?: return
        publish(planId, WechatDirectChatTransitionStatus.SERVICE_UNAVAILABLE)
    }

    @Synchronized
    fun clear() {
        request = null
        confirmedRequest = null
    }

    private fun publish(planId: String, status: WechatDirectChatTransitionStatus) {
        mutableOutcomes.tryEmit(WechatDirectChatTransitionOutcome(planId, status))
    }

    private fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.encodeToByteArray(), right.encodeToByteArray())

    private companion object {
        val TRANSITION_WINDOW: Duration = Duration.ofSeconds(3)
    }
}
