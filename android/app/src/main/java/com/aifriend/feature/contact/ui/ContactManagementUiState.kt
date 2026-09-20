package com.aifriend.feature.contact.ui

import com.aifriend.contract.model.Contact
import com.aifriend.feature.wechat.WechatLocalVerificationCaptureState

/**
 * 联系人管理页面状态。
 *
 * @author codex
 * @since 2026-08-09
 */
data class ContactManagementUiState(
    val contacts: List<Contact> = emptyList(),
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isPreparingDemoContact: Boolean = false,
    val isCheckingDemoReadiness: Boolean = false,
    val safetyCommandsReady: Boolean = false,
    val demoReadinessCheckFailed: Boolean = false,
    val errorMessage: String? = null,
    val informationMessage: String? = null,
    val localVerification: PendingContactLocalVerification? = null,
    val pendingUnbind: PendingContactUnbind? = null,
    val unbindingContactId: String? = null,
)

/** 微信资料未提供名称时，使用已经登记的第一个称呼。 */
internal fun Contact.userFacingLabel(): String = remark
    ?.takeIf { it.isNotBlank() }
    ?: displayName?.takeIf { it.isNotBlank() }
    ?: aliases.orEmpty()
        .firstNotNullOfOrNull { alias -> alias.displayText.takeIf { it.isNotBlank() } }
    ?: "当前已绑定亲友"

/** 当前页面内存中的本机验证编排，不包含微信号或页面内容。 */
data class PendingContactLocalVerification(
    val contactId: String,
    val contactLabel: String,
    val sourceContactVersion: Long,
    val capture: WechatLocalVerificationCaptureState,
    val isSubmitting: Boolean = false,
)

/**
 * 解除绑定二次确认状态。只保存当前页面已经加载的联系人版本，避免使用隐藏或过期对象。
 *
 * @author codex
 * @since 2026-08-09
 */
data class PendingContactUnbind(
    val contactId: String,
    val contactLabel: String,
    val expectedContactVersion: Long,
    val stage: ContactUnbindConfirmationStage,
)

/**
 * 解除绑定确认阶段。
 *
 * @author codex
 * @since 2026-08-09
 */
enum class ContactUnbindConfirmationStage {
    WARNING,
    FINAL_CONFIRMATION,
}
