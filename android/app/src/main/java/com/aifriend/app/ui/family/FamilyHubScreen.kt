package com.aifriend.app.ui.family

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aifriend.app.ui.InvitationUiState
import com.aifriend.app.ui.routeInvitationShare
import com.aifriend.app.ui.components.ActionCard
import com.aifriend.app.ui.components.ActionTone
import com.aifriend.app.ui.components.ElderPage
import com.aifriend.app.ui.components.ErrorPanel
import com.aifriend.app.ui.components.InfoPanel
import com.aifriend.app.ui.components.InvitationRevocationConfirmationHost
import com.aifriend.app.ui.components.LoadingContent
import com.aifriend.app.ui.components.PageTitle
import com.aifriend.app.ui.components.SectionCard
import com.aifriend.contract.model.Contact
import com.aifriend.contract.model.ContactStatus
import com.aifriend.feature.contact.ui.ContactManagementUiState
import com.aifriend.feature.invitation.PendingInvitation
import com.aifriend.feature.invitation.RestoredInvitation

/** 家人页集中承载亲友绑定、联系人和邀请。 */
@Composable
internal fun FamilyHubScreen(
    invitationState: InvitationUiState,
    contactState: ContactManagementUiState,
    onOpenFamilySetup: () -> Unit,
    onOpenContacts: () -> Unit,
    onCreateInvitation: () -> Unit,
    onRefreshInvitations: () -> Unit,
    onRefreshFamily: () -> Unit,
    onRevokeInvitation: (String) -> Unit,
    onShareInvitation: (String) -> Unit,
) {
    InvitationRevocationConfirmationHost(onConfirmed = onRevokeInvitation) { requestRevocation ->
        ElderPage {
            PageTitle("家人", "管理允许联系的亲友")
            SectionCard(
                title = "家人协助",
                support = "只能在您的手机上当面完成。",
            ) {
                ActionCard(
                    title = "家人帮我设置",
                    support = "按步骤完成邀请、联系人和必要设置",
                    onClick = onOpenFamilySetup,
                    tone = ActionTone.EMPHASIZED,
                )
            }
            SectionCard(
                title = "联系人",
                support = "只显示已经绑定或正在验证的亲友。",
            ) {
                FamilyContactStatus(
                    state = contactState,
                    onRefreshFamily = onRefreshFamily,
                )
                ActionCard(
                    title = "管理联系人",
                    support = "查看亲友、管理称呼或解除绑定",
                    onClick = onOpenContacts,
                )
            }
            SectionCard(
                title = "邀请亲友",
                support = "邀请链接只在您明确点击分享时交给微信。",
            ) {
                InvitationContent(
                    state = invitationState,
                    onCreate = onCreateInvitation,
                    onRefresh = onRefreshInvitations,
                    onShare = onShareInvitation,
                    onRevoke = requestRevocation,
                )
            }
        }
    }
}

@Composable
private fun InvitationContent(
    state: InvitationUiState,
    onCreate: () -> Unit,
    onRefresh: () -> Unit,
    onShare: (String) -> Unit,
    onRevoke: (String) -> Unit,
) {
    when (state) {
        InvitationUiState.Idle,
        InvitationUiState.Revoked -> ActionCard(
            title = "创建邀请",
            support = "邀请链接二十四小时内有效",
            onClick = onCreate,
            tone = ActionTone.EMPHASIZED,
        )
        InvitationUiState.Restoring -> LoadingContent("正在读取待处理邀请…")
        is InvitationUiState.Recovered -> RecoveredInvitationActions(
            invitations = state.invitations,
            onRevokeInvitation = onRevoke,
        )
        InvitationUiState.Creating -> LoadingContent("正在创建邀请…")
        is InvitationUiState.Active -> InvitationActions(
            invitation = state.invitation,
            onShareInvitation = onShare,
            onRevokeInvitation = onRevoke,
            onRefreshInvitation = onRefresh,
        )
        is InvitationUiState.Revoking -> LoadingContent("正在撤销邀请…")
        is InvitationUiState.RestoreError -> {
            ErrorPanel(state.message)
            ActionCard("重新读取邀请", "从服务器读取仍在有效期内的邀请", onRefresh)
        }
        is InvitationUiState.Error -> {
            ErrorPanel(state.message)
            val invitation = state.invitation
            if (state.recoveredInvitations.isNotEmpty()) {
                RecoveredInvitationActions(
                    invitations = state.recoveredInvitations,
                    onRevokeInvitation = onRevoke,
                )
            } else if (invitation == null) {
                ActionCard("重新创建邀请", "再次生成二十四小时有效链接", onCreate)
            } else {
                InvitationActions(
                    invitation = invitation,
                    onShareInvitation = onShare,
                    onRevokeInvitation = onRevoke,
                    onRefreshInvitation = onRefresh,
                )
            }
        }
    }
}

@Composable
private fun InvitationActions(
    invitation: PendingInvitation,
    onShareInvitation: (String) -> Unit,
    onRevokeInvitation: (String) -> Unit,
    onRefreshInvitation: () -> Unit,
) {
    InfoPanel("邀请已创建，二十四小时内有效。分享时请选择微信。")
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
        onClick = {
            routeInvitationShare(
                invitation = invitation,
                onShare = onShareInvitation,
                onRefresh = onRefreshInvitation,
            )
        },
    ) {
        Text("分享邀请")
    }
    OutlinedButton(
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
        onClick = { onRevokeInvitation(invitation.invitationId) },
    ) {
        Text("撤销邀请")
    }
}

@Composable
private fun FamilyContactStatus(
    state: ContactManagementUiState,
    onRefreshFamily: () -> Unit,
) {
    when {
        state.isInitialLoading -> LoadingContent("正在读取家人状态…")
        state.errorMessage != null && state.contacts.isEmpty() -> {
            ErrorPanel("家人状态暂时无法读取，请稍后刷新。")
        }
        else -> {
            val summary = state.contacts.toFamilyContactSummary()
            InfoPanel(
                if (summary.totalCount == 0) {
                    "还没有已绑定或正在验证的亲友。"
                } else {
                    "共 ${summary.totalCount} 位亲友；可以联系 ${summary.activeCount} 位，" +
                        "待设置称呼 ${summary.aliasRequiredCount} 位。"
                },
            )
            if (summary.localVerificationRequiredCount > 0) {
                InfoPanel(
                        "有 ${summary.localVerificationRequiredCount} 位亲友已同意邀请，" +
                        "还需在老人手机上确认一次联系人。",
                )
            }
            if (summary.reverificationRequiredCount > 0) {
                InfoPanel(
                    "有 ${summary.reverificationRequiredCount} 位亲友需要在老人手机上重新确认一次。",
                )
            }
        }
    }
    OutlinedButton(
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
        enabled = !state.isInitialLoading && !state.isRefreshing,
        onClick = onRefreshFamily,
    ) {
        Text(if (state.isRefreshing) "正在刷新家人状态" else "刷新家人状态")
    }
}

/** 家人页联系人最小状态摘要。 */
internal data class FamilyContactSummary(
    val totalCount: Int,
    val activeCount: Int,
    val aliasRequiredCount: Int,
    val localVerificationRequiredCount: Int,
    val reverificationRequiredCount: Int,
)

/**
 * 将联系人列表折叠为家人页可见的非敏感状态数量。
 *
 * @return 不包含联系人名称或标识的状态摘要
 */
internal fun List<Contact>.toFamilyContactSummary(): FamilyContactSummary = FamilyContactSummary(
    totalCount = count { it.status != ContactStatus.REVOKED },
    activeCount = count { it.status == ContactStatus.ACTIVE },
    aliasRequiredCount = count { it.status == ContactStatus.ACTIVE_NO_ALIAS },
    localVerificationRequiredCount = count { it.status == ContactStatus.PENDING_LOCAL_VERIFY },
    reverificationRequiredCount = count { it.status == ContactStatus.REVERIFY_REQUIRED },
)

@Composable
private fun RecoveredInvitationActions(
    invitations: List<RestoredInvitation>,
    onRevokeInvitation: (String) -> Unit,
) {
    InfoPanel(
        "发现 ${invitations.size} 个等待亲友确认的邀请。旧分享链接不会保存在本机，" +
            "如需重新分享，请先撤销旧邀请。",
    )
    invitations.forEachIndexed { index, invitation ->
        ActionCard(
            title = "待处理邀请 ${index + 1}",
            support = "等待亲友确认，点击后撤销",
            onClick = { onRevokeInvitation(invitation.invitationId) },
        )
    }
}
