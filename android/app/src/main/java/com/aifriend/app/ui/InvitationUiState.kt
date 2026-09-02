package com.aifriend.app.ui

import com.aifriend.feature.invitation.PendingInvitation
import com.aifriend.feature.invitation.RestoredInvitation
import java.time.Instant

/**
 * 当前进程内的邀请创建、分享和撤销状态。
 *
 * @author codex
 * @since 2026-08-07
 */
sealed interface InvitationUiState {
    data object Idle : InvitationUiState

    data object Restoring : InvitationUiState

    data class Recovered(val invitations: List<RestoredInvitation>) : InvitationUiState

    data object Creating : InvitationUiState

    data class Active(val invitation: PendingInvitation) : InvitationUiState

    data class Revoking(
        val invitationId: String,
        val activeInvitation: PendingInvitation? = null,
        val recoveredInvitations: List<RestoredInvitation> = emptyList(),
    ) : InvitationUiState

    data object Revoked : InvitationUiState

    data class RestoreError(val message: String) : InvitationUiState

    data class Error(
        val message: String,
        val invitation: PendingInvitation? = null,
        val recoveredInvitations: List<RestoredInvitation> = emptyList(),
    ) : InvitationUiState
}

/** 只交给微信的邀请分享请求；完整地址只在当前调用栈内存中短暂存在。 */
internal data class WechatInvitationShareRequest(
    val packageName: String,
    val mimeType: String,
    val text: String,
)

/**
 * 在交给系统分享前复核邀请仍有效；过期或缺失地址时只刷新服务端状态。
 *
 * @param invitation 当前进程内待分享邀请
 * @param now 当前 UTC 时间点
 * @param onShare 仅在邀请有效时接收完整分享地址
 * @param onRefresh 邀请不可分享时重新读取服务端状态
 */
internal fun routeInvitationShare(
    invitation: PendingInvitation,
    now: Instant = Instant.now(),
    onShare: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    if (
        invitation.shareUrl.isNotBlank() &&
        invitation.expiresAt.toInstant().isAfter(now)
    ) {
        onShare(invitation.shareUrl)
    } else {
        onRefresh()
    }
}

/**
 * 把有效邀请定向交给微信；微信缺失或启动异常时只报告失败，不改写分享地址。
 *
 * @param shareUrl 当前进程内的完整邀请地址
 * @param launchWechat 启动微信的同步边界，返回是否成功交给系统
 * @param onFailure 微信未启动时的应用内失败反馈
 */
internal fun routeWechatInvitationShare(
    shareUrl: String,
    launchWechat: (WechatInvitationShareRequest) -> Boolean,
    onFailure: () -> Unit,
) {
    if (shareUrl.isBlank()) {
        onFailure()
        return
    }
    val request = WechatInvitationShareRequest(
        packageName = WECHAT_PACKAGE_NAME,
        mimeType = "text/plain",
        text = shareUrl,
    )
    val launched = runCatching { launchWechat(request) }.getOrDefault(false)
    if (!launched) onFailure()
}

/** 微信分享失败时保留仍可重试或撤销的当前邀请。 */
internal fun InvitationUiState.withWechatShareFailure(): InvitationUiState = when (this) {
    is InvitationUiState.Active -> InvitationUiState.Error(
        message = WECHAT_SHARE_FAILURE_MESSAGE,
        invitation = invitation,
    )
    is InvitationUiState.Error -> if (invitation == null) {
        this
    } else {
        copy(message = WECHAT_SHARE_FAILURE_MESSAGE)
    }
    else -> this
}

internal const val WECHAT_PACKAGE_NAME = "com.tencent.mm"
internal const val WECHAT_SHARE_FAILURE_MESSAGE =
    "微信没有打开，邀请没有发出。请确认已安装微信后重试。"
