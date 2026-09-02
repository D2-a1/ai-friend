package com.aifriend.feature.wechat

import com.aifriend.contract.model.Intent
import com.aifriend.contract.model.TaskSession
import com.aifriend.contract.model.TaskState
import com.aifriend.contract.model.WechatActionPlan
import com.aifriend.contract.model.WechatActionType
import java.security.MessageDigest
import java.time.Duration
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** 无障碍事件允许转换出的最小页面类型，不包含聊天正文或页面文字。 */
enum class WechatPageType {
    HOME,
    DIRECT_CHAT,
    CONTACT_PROFILE,
    VOICE_CALL_CONFIRMATION,
    VIDEO_CALL_CONFIRMATION,
    VOICE_CALL_ACTIVE,
    VIDEO_CALL_ACTIVE,
    UNSUPPORTED,
}

/**
 * 无障碍事件的最小化只读快照。
 *
 * 页面签名和定位只允许保存 SHA-256；不得加入节点文字、聊天正文、头像、截图、坐标、
 * 验证码、支付信息或非目标联系人数据。
 */
data class WechatPageSnapshot(
    val packageName: String,
    val wechatVersion: String,
    val ruleVersion: String,
    val locatorVersion: String,
    val pageType: WechatPageType,
    val pageSignatureSha256: String,
    val targetLocatorSha256: String?,
    val targetMatchCount: Int,
    val actionNodeMatchCount: Int,
    val capturedAt: OffsetDateTime,
) {
    override fun toString(): String =
        "WechatPageSnapshot(packageName=$packageName, wechatVersion=$wechatVersion, " +
            "ruleVersion=$ruleVersion, locatorVersion=$locatorVersion, pageType=$pageType, " +
            "pageSignatureSha256=<redacted>, targetLocatorSha256=<redacted>, " +
            "targetMatchCount=$targetMatchCount, actionNodeMatchCount=$actionNodeMatchCount, " +
            "capturedAt=$capturedAt)"
}

/**
 * 未来由可信定位凭据端口提供的当前计划目标证明。
 *
 * 当前 OpenAPI 尚未提供该事实，因此生产上下文固定返回空，不能用昵称、头像、搜索顺序或
 * 坐标代替。摘要只允许当前进程比较，不进入 Room、DataStore 或日志。
 */
data class VerifiedWechatTargetLocatorProof(
    val planId: String,
    val contactId: String,
    val contactVersion: Long,
    val wechatVersion: String,
    val locatorVersion: String,
    val targetLocatorSha256: String,
    val issuedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val keyId: String,
) {
    override fun toString(): String =
        "VerifiedWechatTargetLocatorProof(planId=<redacted>, contactId=<redacted>, " +
            "contactVersion=$contactVersion, wechatVersion=$wechatVersion, " +
            "locatorVersion=$locatorVersion, targetLocatorSha256=<redacted>, " +
            "issuedAt=$issuedAt, expiresAt=$expiresAt, keyId=$keyId)"
}

/** 已通过外部真机回归和签名校验的能力矩阵快照。 */
data class WechatCapabilitySnapshot(
    val remotelyEnabled: Boolean,
    val signedRulesTrusted: Boolean,
    val combinationApproved: Boolean,
    val packageName: String,
    val wechatVersion: String,
    val ruleVersion: String,
    val locatorVersion: String,
    val compatibleMinimumRuleVersions: Set<String>,
    val allowedActions: Set<WechatActionType>,
    val allowedPageTypes: Map<WechatActionType, Set<WechatPageType>>,
    val allowedPageSignatures: Map<WechatActionType, Set<String>>,
    val appBuildSha256: String,
    val signingCertificateSha256: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidSdkInt: Int,
    val allowedPageSignaturesByType: Map<
        WechatActionType,
        Map<WechatPageType, Set<String>>,
    > = emptyMap(),
) {
    fun pageSignatures(
        action: WechatActionType,
        pageType: WechatPageType,
    ): Set<String> = allowedPageSignaturesByType[action]?.get(pageType)
        ?: allowedPageSignatures[action].orEmpty().takeIf {
            allowedPageTypes[action] == setOf(pageType)
        }.orEmpty()

    override fun toString(): String =
        "WechatCapabilitySnapshot(remotelyEnabled=$remotelyEnabled, " +
            "signedRulesTrusted=$signedRulesTrusted, combinationApproved=$combinationApproved, " +
            "packageName=$packageName, wechatVersion=$wechatVersion, ruleVersion=$ruleVersion, " +
            "locatorVersion=$locatorVersion, compatibleMinimumRuleVersions=" +
            compatibleMinimumRuleVersions.sorted() + ", allowedActions=" +
            allowedActions.map { it.value }.sorted() + ", allowedPageTypes=<redacted>, " +
            "allowedPageSignatures=<redacted>, appBuildSha256=<redacted>, " +
            "signingCertificateSha256=<redacted>, deviceManufacturer=$deviceManufacturer, " +
            "deviceModel=$deviceModel, androidSdkInt=$androidSdkInt)"

    companion object {
        /** 正式规则、定位凭据和真机能力矩阵接入前的唯一默认值。 */
        fun disabled(): WechatCapabilitySnapshot = WechatCapabilitySnapshot(
            remotelyEnabled = false,
            signedRulesTrusted = false,
            combinationApproved = false,
            packageName = WECHAT_PACKAGE,
            wechatVersion = RESERVED_DISABLED,
            ruleVersion = RESERVED_DISABLED,
            locatorVersion = RESERVED_DISABLED,
            compatibleMinimumRuleVersions = emptySet(),
            allowedActions = emptySet(),
            allowedPageTypes = emptyMap(),
            allowedPageSignatures = emptyMap(),
            appBuildSha256 = "",
            signingCertificateSha256 = "",
            deviceManufacturer = "",
            deviceModel = "",
            androidSdkInt = 0,
            allowedPageSignaturesByType = emptyMap(),
        )

        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val RESERVED_DISABLED = "RESERVED_DISABLED"
    }
}

/** 当前进程执行上下文；不允许持久化或跨任务复用。 */
data class WechatExecutionContext(
    val capability: WechatCapabilitySnapshot,
    val pageSnapshot: WechatPageSnapshot?,
    val targetLocatorProof: VerifiedWechatTargetLocatorProof?,
)

/** 准入拒绝原因只描述安全边界，不携带微信标识、正文或页面节点。 */
enum class WechatExecutionDenial {
    TASK_NOT_CURRENT,
    SESSION_NOT_EXECUTING,
    SESSION_EXPIRED,
    PLAN_EXPIRED,
    SUMMARY_MISMATCH,
    CONTACT_MISMATCH,
    INTENT_ACTION_MISMATCH,
    ACTION_PAYLOAD_INVALID,
    REMOTELY_DISABLED,
    RULE_SET_UNTRUSTED,
    CAPABILITY_NOT_APPROVED,
    RULE_VERSION_UNSUPPORTED,
    ACTION_NOT_ALLOWED,
    TARGET_LOCATOR_PROOF_MISSING,
    TARGET_LOCATOR_PROOF_INVALID,
    PAGE_SNAPSHOT_MISSING,
    PAGE_SNAPSHOT_STALE,
    WECHAT_PACKAGE_MISMATCH,
    WECHAT_VERSION_MISMATCH,
    CALIBRATION_PROFILE_MISSING,
    RULE_SNAPSHOT_MISMATCH,
    PAGE_UNSUPPORTED,
    PAGE_SIGNATURE_UNSUPPORTED,
    TARGET_LOCATOR_MISMATCH,
    TARGET_NOT_UNIQUE,
    ACTION_NODE_NOT_UNIQUE,
}

/** 通过只代表允许进入未来的一次性执行器，不代表已经点击、发送或呼叫。 */
data class WechatExecutionAdmissionResult(
    val allowed: Boolean,
    val denial: WechatExecutionDenial?,
) {
    companion object {
        fun allow(): WechatExecutionAdmissionResult = WechatExecutionAdmissionResult(true, null)

        fun deny(reason: WechatExecutionDenial): WechatExecutionAdmissionResult =
            WechatExecutionAdmissionResult(false, reason)
    }
}

/**
 * 微信有限动作的纯判定准入防火墙。
 *
 * 本类无 Android API、网络、文件、日志或点击副作用。全部事实必须精确匹配；未知、缺失、
 * 过期、不唯一或未经签名/真机批准时拒绝，且语音通话与视频通话不能互相降级。
 */
@Singleton
class WechatExecutionAdmission @Inject constructor() {

    fun evaluate(
        plan: WechatActionPlan,
        session: TaskSession,
        taskCurrent: Boolean,
        context: WechatExecutionContext,
        now: OffsetDateTime,
    ): WechatExecutionAdmissionResult {
        if (!taskCurrent) return deny(WechatExecutionDenial.TASK_NOT_CURRENT)
        if (session.state != TaskState.EXECUTING) {
            return deny(WechatExecutionDenial.SESSION_NOT_EXECUTING)
        }
        if (!session.expiresAt.isAfter(now)) return deny(WechatExecutionDenial.SESSION_EXPIRED)
        if (!plan.expiresAt.isAfter(now)) return deny(WechatExecutionDenial.PLAN_EXPIRED)
        if (plan.summaryHash != session.summaryHash) {
            return deny(WechatExecutionDenial.SUMMARY_MISMATCH)
        }
        val understanding = session.understanding
        if (understanding?.contact?.id != plan.contactId) {
            return deny(WechatExecutionDenial.CONTACT_MISMATCH)
        }
        if (!actionMatchesIntent(plan.action, understanding.intent)) {
            return deny(WechatExecutionDenial.INTENT_ACTION_MISMATCH)
        }
        if (!actionPayloadValid(plan)) return deny(WechatExecutionDenial.ACTION_PAYLOAD_INVALID)

        val capability = context.capability
        if (!capability.remotelyEnabled) return deny(WechatExecutionDenial.REMOTELY_DISABLED)
        if (!capability.signedRulesTrusted) return deny(WechatExecutionDenial.RULE_SET_UNTRUSTED)
        if (!capability.combinationApproved) {
            return deny(WechatExecutionDenial.CAPABILITY_NOT_APPROVED)
        }
        if (decodeSha256(capability.appBuildSha256) == null ||
            decodeSha256(capability.signingCertificateSha256) == null ||
            capability.deviceManufacturer.isBlank() || capability.deviceModel.isBlank() ||
            capability.androidSdkInt < MINIMUM_ANDROID_SDK ||
            capability.wechatVersion.isBlank() || capability.ruleVersion.isBlank() ||
            capability.locatorVersion.isBlank()
        ) {
            return deny(WechatExecutionDenial.CAPABILITY_NOT_APPROVED)
        }
        if (plan.minimumRuleVersion !in capability.compatibleMinimumRuleVersions) {
            return deny(WechatExecutionDenial.RULE_VERSION_UNSUPPORTED)
        }
        if (plan.action !in capability.allowedActions) {
            return deny(WechatExecutionDenial.ACTION_NOT_ALLOWED)
        }

        val proof = context.targetLocatorProof
            ?: return deny(WechatExecutionDenial.TARGET_LOCATOR_PROOF_MISSING)
        if (proof.planId != plan.planId || proof.contactId != plan.contactId ||
            proof.contactVersion < 0 || proof.keyId.isBlank() || proof.issuedAt.isAfter(now) ||
            !proof.expiresAt.isAfter(now) ||
            proof.expiresAt.toInstant() != plan.expiresAt.toInstant() ||
            proof.wechatVersion != capability.wechatVersion ||
            proof.locatorVersion != capability.locatorVersion ||
            decodeSha256(proof.targetLocatorSha256) == null
        ) {
            return deny(WechatExecutionDenial.TARGET_LOCATOR_PROOF_INVALID)
        }

        val page = context.pageSnapshot ?: return deny(WechatExecutionDenial.PAGE_SNAPSHOT_MISSING)
        if (page.capturedAt.isAfter(now) || Duration.between(page.capturedAt, now) > MAX_SNAPSHOT_AGE) {
            return deny(WechatExecutionDenial.PAGE_SNAPSHOT_STALE)
        }
        if (page.packageName != capability.packageName) {
            return deny(WechatExecutionDenial.WECHAT_PACKAGE_MISMATCH)
        }
        if (page.wechatVersion != capability.wechatVersion) {
            return deny(WechatExecutionDenial.WECHAT_VERSION_MISMATCH)
        }
        if (page.ruleVersion != capability.ruleVersion ||
            page.locatorVersion != capability.locatorVersion
        ) {
            return deny(WechatExecutionDenial.RULE_SNAPSHOT_MISMATCH)
        }
        if (page.pageType !in capability.allowedPageTypes[plan.action].orEmpty()) {
            return deny(WechatExecutionDenial.PAGE_UNSUPPORTED)
        }
        val pageSignature = decodeSha256(page.pageSignatureSha256)
            ?: return deny(WechatExecutionDenial.PAGE_SIGNATURE_UNSUPPORTED)
        val signatureAllowed = capability.pageSignatures(plan.action, page.pageType)
            .mapNotNull(::decodeSha256)
            .any { expected -> MessageDigest.isEqual(expected, pageSignature) }
        if (!signatureAllowed) return deny(WechatExecutionDenial.PAGE_SIGNATURE_UNSUPPORTED)
        val pageLocator = page.targetLocatorSha256?.let(::decodeSha256)
            ?: return deny(WechatExecutionDenial.TARGET_LOCATOR_MISMATCH)
        val proofLocator = checkNotNull(decodeSha256(proof.targetLocatorSha256))
        if (!MessageDigest.isEqual(pageLocator, proofLocator)) {
            return deny(WechatExecutionDenial.TARGET_LOCATOR_MISMATCH)
        }
        if (page.targetMatchCount != 1) return deny(WechatExecutionDenial.TARGET_NOT_UNIQUE)
        if (page.actionNodeMatchCount != 1) {
            return deny(WechatExecutionDenial.ACTION_NODE_NOT_UNIQUE)
        }
        return WechatExecutionAdmissionResult.allow()
    }

    private fun actionMatchesIntent(action: WechatActionType, intent: Intent): Boolean = when (action) {
        WechatActionType.SEND_AUDIO_AND_TEXT -> intent == Intent.SEND_MESSAGE
        WechatActionType.START_VOICE_CALL -> intent == Intent.VOICE_CALL
        WechatActionType.START_VIDEO_CALL -> intent == Intent.VIDEO_CALL
    }

    private fun actionPayloadValid(plan: WechatActionPlan): Boolean = when (plan.action) {
        WechatActionType.SEND_AUDIO_AND_TEXT -> !plan.audioObjectId.isNullOrBlank()
        WechatActionType.START_VOICE_CALL, WechatActionType.START_VIDEO_CALL ->
            plan.audioObjectId == null
    }

    private fun decodeSha256(value: String): ByteArray? {
        if (value.length != SHA256_HEX_LENGTH || value.any { it.digitToIntOrNull(16) == null }) {
            return null
        }
        return ByteArray(SHA256_BYTE_LENGTH) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun deny(reason: WechatExecutionDenial): WechatExecutionAdmissionResult =
        WechatExecutionAdmissionResult.deny(reason)

    private companion object {
        val MAX_SNAPSHOT_AGE: Duration = Duration.ofSeconds(5)
        const val SHA256_HEX_LENGTH = 64
        const val SHA256_BYTE_LENGTH = 32
        const val MINIMUM_ANDROID_SDK = 29
    }
}
