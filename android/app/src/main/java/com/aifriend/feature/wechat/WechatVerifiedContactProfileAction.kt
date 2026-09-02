package com.aifriend.feature.wechat

import android.view.accessibility.AccessibilityNodeInfo
import com.aifriend.contract.model.WechatActionPlan
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

/** 二次准入后允许消费一次的联系人资料页动作请求，不包含消息原声或正文。 */
data class WechatVerifiedContactProfileActionRequest(
    val planId: String,
    val action: WechatActionType,
    val capability: WechatCapabilitySnapshot,
    val targetLocatorProof: VerifiedWechatTargetLocatorProof,
    val locatorSalt: String,
    val openedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
) {
    fun asObservationRequest(): WechatPageObservationRequest = WechatPageObservationRequest(
        planId = planId,
        action = action,
        capability = capability,
        targetLocatorProof = targetLocatorProof,
        locatorSalt = locatorSalt,
        openedAt = openedAt,
        expiresAt = expiresAt,
        expectedPageType = WechatPageType.CONTACT_PROFILE,
    )

    override fun toString(): String =
        "WechatVerifiedContactProfileActionRequest(planId=<redacted>, action=$action, " +
            "capability=$capability, " +
            "targetLocatorProof=$targetLocatorProof, locatorSalt=<redacted>, " +
            "openedAt=$openedAt, expiresAt=$expiresAt)"
}

/** 一次性资料页动作的有限本机结果；成功只表示系统接受了点击，不表示内容已发送。 */
enum class WechatVerifiedContactProfileActionStatus {
    CLICK_REQUEST_ACCEPTED,
    SERVICE_UNAVAILABLE,
    ROOT_UNAVAILABLE,
    PAGE_REVALIDATION_FAILED,
    ACTION_NOT_UNIQUE,
    ACTION_NOT_CLICKABLE,
    CLICK_REQUEST_REJECTED,
}

data class WechatVerifiedContactProfileActionOutcome(
    val planId: String,
    val status: WechatVerifiedContactProfileActionStatus,
)

/**
 * 当前进程一次性资料页动作门闩。
 *
 * 请求只在二次准入通过后形成；辅助服务必须先消费再尝试点击，因此事件重放、页面重复事件或
 * 点击返回失败都不会自动重试。退出、取消、超时或服务中断会清除请求。
 */
@Singleton
class WechatVerifiedContactProfileActionBroker @Inject constructor() {
    private val mutableRequests = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val mutableOutcomes = MutableSharedFlow<WechatVerifiedContactProfileActionOutcome>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var request: WechatVerifiedContactProfileActionRequest? = null

    val requests: SharedFlow<String> = mutableRequests.asSharedFlow()
    val outcomes: SharedFlow<WechatVerifiedContactProfileActionOutcome> =
        mutableOutcomes.asSharedFlow()

    fun arm(
        plan: WechatActionPlan,
        capability: WechatCapabilitySnapshot,
        proof: VerifiedWechatTargetLocatorProof,
        now: OffsetDateTime,
    ) {
        val armed = synchronized(this) {
            request = null
            val rawProof = plan.targetLocatorProof
            val expiresAt = listOf(
                now.plus(ACTION_WINDOW),
                plan.expiresAt,
                proof.expiresAt,
            ).minBy { it.toInstant() }
            val transitionPageType = plan.action.transitionPageType()
            if (!capability.remotelyEnabled || !capability.signedRulesTrusted ||
                !capability.combinationApproved || plan.action !in capability.allowedActions ||
                WechatPageType.CONTACT_PROFILE !in
                capability.allowedPageTypes[plan.action].orEmpty() ||
                transitionPageType !in
                capability.allowedPageTypes[plan.action].orEmpty() ||
                capability.pageSignatures(
                    plan.action,
                    WechatPageType.CONTACT_PROFILE,
                ).isEmpty() ||
                capability.pageSignatures(plan.action, transitionPageType).isEmpty() ||
                proof.planId != plan.planId || proof.contactId != plan.contactId ||
                rawProof.salt.length != SALT_HEX_LENGTH ||
                rawProof.salt.any { it.digitToIntOrNull(16) == null } ||
                !expiresAt.isAfter(now)
            ) {
                false
            } else {
                request = WechatVerifiedContactProfileActionRequest(
                    planId = plan.planId,
                    action = plan.action,
                    capability = capability,
                    targetLocatorProof = proof,
                    locatorSalt = rawProof.salt,
                    openedAt = now,
                    expiresAt = expiresAt,
                )
                true
            }
        }
        if (!armed) return
        if (mutableRequests.subscriptionCount.value == 0) {
            synchronized(this) { request = null }
            publish(
                WechatVerifiedContactProfileActionOutcome(
                    plan.planId,
                    WechatVerifiedContactProfileActionStatus.SERVICE_UNAVAILABLE,
                ),
            )
            return
        }
        mutableRequests.tryEmit(plan.planId)
    }

    /** 正确包名、计划和时限全部匹配后先清除请求，再把唯一副本交给执行器。 */
    @Synchronized
    fun take(
        planId: String,
        packageName: String,
        now: OffsetDateTime,
    ): WechatVerifiedContactProfileActionRequest? {
        val current = request ?: return null
        if (now.isBefore(current.openedAt) || !current.expiresAt.isAfter(now)) {
            request = null
            return null
        }
        if (current.planId != planId || current.capability.packageName != packageName) return null
        request = null
        return current
    }

    fun publish(outcome: WechatVerifiedContactProfileActionOutcome) {
        mutableOutcomes.tryEmit(outcome)
    }

    /** 服务中断时只结束尚未消费的请求并返回有限失败，不产生重试。 */
    fun interrupt() {
        val planId = synchronized(this) {
            val currentPlanId = request?.planId
            request = null
            currentPlanId
        } ?: return
        publish(
            WechatVerifiedContactProfileActionOutcome(
                planId,
                WechatVerifiedContactProfileActionStatus.SERVICE_UNAVAILABLE,
            ),
        )
    }

    @Synchronized
    fun clear() {
        request = null
    }

    private companion object {
        val ACTION_WINDOW: Duration = Duration.ofSeconds(2)
        const val SALT_HEX_LENGTH = 32
    }
}

/** 点击前对重新读取的资料页快照做独立、精确、无副作用复验。 */
internal object WechatVerifiedContactProfileActionPolicy {
    fun accepts(
        request: WechatVerifiedContactProfileActionRequest,
        snapshot: WechatPageSnapshot,
        now: OffsetDateTime,
    ): Boolean {
        if (now.isBefore(request.openedAt) || !request.expiresAt.isAfter(now) ||
            snapshot.capturedAt.isBefore(request.openedAt) || snapshot.capturedAt.isAfter(now) ||
            snapshot.packageName != request.capability.packageName ||
            snapshot.wechatVersion != request.capability.wechatVersion ||
            snapshot.ruleVersion != request.capability.ruleVersion ||
            snapshot.locatorVersion != request.capability.locatorVersion ||
            snapshot.pageType != WechatPageType.CONTACT_PROFILE ||
            snapshot.targetMatchCount != 1 || snapshot.actionNodeMatchCount != 1
        ) {
            return false
        }
        val locator = snapshot.targetLocatorSha256 ?: return false
        if (!constantTimeEquals(locator, request.targetLocatorProof.targetLocatorSha256)) return false
        return request.capability.pageSignatures(
            request.action,
            WechatPageType.CONTACT_PROFILE,
        )
            .any { allowed -> constantTimeEquals(allowed, snapshot.pageSignatureSha256) }
    }

    private fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.encodeToByteArray(), right.encodeToByteArray())
}

/** 重新读取同一资料页后，只对唯一、精确且自身可点击的“发消息”节点请求一次点击。 */
@Singleton
class WechatVerifiedContactProfileActionExecutor @Inject constructor(
    private val pageReader: WechatAccessibilityPageReader,
) {
    fun execute(
        root: AccessibilityNodeInfo?,
        request: WechatVerifiedContactProfileActionRequest,
        now: OffsetDateTime,
    ): WechatVerifiedContactProfileActionOutcome {
        val rootNode = root ?: return outcome(
            request,
            WechatVerifiedContactProfileActionStatus.ROOT_UNAVAILABLE,
        )
        if (rootNode.packageName?.toString() != request.capability.packageName) {
            return outcome(request, WechatVerifiedContactProfileActionStatus.PAGE_REVALIDATION_FAILED)
        }
        val snapshot = pageReader.read(rootNode, request.asObservationRequest(), now)
            ?: return outcome(
                request,
                WechatVerifiedContactProfileActionStatus.PAGE_REVALIDATION_FAILED,
            )
        if (!WechatVerifiedContactProfileActionPolicy.accepts(request, snapshot, now)) {
            return outcome(request, WechatVerifiedContactProfileActionStatus.PAGE_REVALIDATION_FAILED)
        }
        val actionQuery = WechatLocalVerificationTextRule.contactProfileActionQuery(request.action)
        val nodes = runCatching { rootNode.findAccessibilityNodeInfosByText(actionQuery) }
            .getOrNull()
            ?: return outcome(request, WechatVerifiedContactProfileActionStatus.ACTION_NOT_UNIQUE)
        return try {
            val exact = nodes.filter { node ->
                WechatLocalVerificationTextRule.isContactProfileAction(node.text, request.action)
            }
            when {
                exact.size != 1 -> outcome(
                    request,
                    WechatVerifiedContactProfileActionStatus.ACTION_NOT_UNIQUE,
                )
                !exact.single().isClickable || !exact.single().isEnabled ||
                    !exact.single().isVisibleToUser -> outcome(
                    request,
                    WechatVerifiedContactProfileActionStatus.ACTION_NOT_CLICKABLE,
                )
                runCatching {
                    exact.single().performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false) -> outcome(
                    request,
                    WechatVerifiedContactProfileActionStatus.CLICK_REQUEST_ACCEPTED,
                )
                else -> outcome(
                    request,
                    WechatVerifiedContactProfileActionStatus.CLICK_REQUEST_REJECTED,
                )
            }
        } finally {
            nodes.forEach { node -> node.recycleOwned() }
        }
    }

    private fun outcome(
        request: WechatVerifiedContactProfileActionRequest,
        status: WechatVerifiedContactProfileActionStatus,
    ): WechatVerifiedContactProfileActionOutcome =
        WechatVerifiedContactProfileActionOutcome(request.planId, status)

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.recycleOwned() {
        if (android.os.Build.VERSION.SDK_INT < 33) recycle()
    }

}

/** 资料页点击后必须出现的下一签名页面；消息与两类通话不能互相降级。 */
internal fun WechatActionType.transitionPageType(): WechatPageType = when (this) {
    WechatActionType.SEND_AUDIO_AND_TEXT -> WechatPageType.DIRECT_CHAT
    WechatActionType.START_VOICE_CALL -> WechatPageType.VOICE_CALL_CONFIRMATION
    WechatActionType.START_VIDEO_CALL -> WechatPageType.VIDEO_CALL_CONFIRMATION
}
