package com.aifriend.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** 统一承载亲友邀请撤销的二次确认，避免页面直接调用服务端撤销。 */
@Composable
internal fun InvitationRevocationConfirmationHost(
    onConfirmed: (String) -> Unit,
    content: @Composable (requestRevocation: (String) -> Unit) -> Unit,
) {
    var pendingInvitationId by remember { mutableStateOf<String?>(null) }

    fun handle(
        action: InvitationRevocationConfirmationAction,
        requestedInvitationId: String? = null,
    ) {
        val decision = resolveInvitationRevocationConfirmation(
            pendingInvitationId = pendingInvitationId,
            action = action,
            requestedInvitationId = requestedInvitationId,
        )
        pendingInvitationId = decision.pendingInvitationId
        decision.revokedInvitationId?.let(onConfirmed)
    }

    content { invitationId ->
        handle(
            action = InvitationRevocationConfirmationAction.REQUEST,
            requestedInvitationId = invitationId,
        )
    }

    if (pendingInvitationId != null) {
        AlertDialog(
            onDismissRequest = {
                handle(InvitationRevocationConfirmationAction.CANCEL)
            },
            title = { Text("确认撤销亲友邀请？") },
            text = {
                Text("撤销后当前分享链接会立即失效。如需邀请亲友，必须重新创建并分享。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        handle(InvitationRevocationConfirmationAction.CONFIRM)
                    },
                ) {
                    Text("确认撤销")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        handle(InvitationRevocationConfirmationAction.CANCEL)
                    },
                ) {
                    Text("保留邀请")
                }
            },
        )
    }
}

/** 邀请撤销确认动作。 */
internal enum class InvitationRevocationConfirmationAction {
    REQUEST,
    CANCEL,
    CONFIRM,
}

/** 邀请撤销确认结果；只有已记录邀请再次确认后才允许调用撤销。 */
internal data class InvitationRevocationConfirmationDecision(
    val pendingInvitationId: String?,
    val revokedInvitationId: String?,
)

/** 将邀请级首次请求、取消和明确确认收口为有限状态。 */
internal fun resolveInvitationRevocationConfirmation(
    pendingInvitationId: String?,
    action: InvitationRevocationConfirmationAction,
    requestedInvitationId: String? = null,
): InvitationRevocationConfirmationDecision = when (action) {
    InvitationRevocationConfirmationAction.REQUEST -> InvitationRevocationConfirmationDecision(
        pendingInvitationId = requestedInvitationId?.takeIf(String::isNotBlank),
        revokedInvitationId = null,
    )
    InvitationRevocationConfirmationAction.CANCEL -> InvitationRevocationConfirmationDecision(
        pendingInvitationId = null,
        revokedInvitationId = null,
    )
    InvitationRevocationConfirmationAction.CONFIRM -> InvitationRevocationConfirmationDecision(
        pendingInvitationId = null,
        revokedInvitationId = pendingInvitationId?.takeIf(String::isNotBlank),
    )
}
