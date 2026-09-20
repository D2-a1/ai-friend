package com.aifriend.app.ui

import com.aifriend.app.ui.components.toChineseUiMessage
import com.aifriend.app.ui.components.InvitationRevocationConfirmationHost
import com.aifriend.feature.invitation.RestoredInvitation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 家人只能在老人手机上当面协助，不产生远程管理身份。 */
@Composable
fun FamilySetupScreen(
    invitationState: InvitationUiState,
    onCreateInvitation: () -> Unit,
    onRefreshInvitations: () -> Unit,
    onShareInvitation: (String) -> Unit,
    onRevokeInvitation: (String) -> Unit,
    onOpenContacts: () -> Unit,
    onOpenSafetyCommands: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    InvitationRevocationConfirmationHost(onConfirmed = onRevokeInvitation) { requestRevocation ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "家人当面协助设置",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineLarge,
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("只能当面协助", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "请家人在老人这部手机上操作。本应用不提供远程控制、家属管理员或代替老人确认。",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "每次联系仍必须命中已绑定白名单，完整复述后由老人明确确认。",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            FamilySetupSection(title = "1. 邀请与绑定亲友") {
                Text(
                    "邀请链接只保留在当前进程，必须由用户明确选择微信分享。",
                    style = MaterialTheme.typography.bodyLarge,
                )
                FamilyInvitationActions(
                    state = invitationState,
                    onCreateInvitation = onCreateInvitation,
                    onRefreshInvitations = onRefreshInvitations,
                    onShareInvitation = onShareInvitation,
                    onRevokeInvitation = requestRevocation,
                )
            }

            FamilySetupSection(title = "2. 检查联系人与称呼") {
                Text(
                    "只管理已绑定的亲友和老人亲自录制的家庭称呼。",
                    style = MaterialTheme.typography.bodyLarge,
                )
                FamilyActionButton("打开我的亲友", onOpenContacts)
            }

            FamilySetupSection(title = "3. 录制安全指令") {
                Text(
                    "帮助老人双录发送消息、拨打电话、取消这次和重新说一遍四类动作指令；任务播报后直接说“确认”或否认词，无需另录。",
                    style = MaterialTheme.typography.bodyLarge,
                )
                FamilyActionButton("打开安全指令录制", onOpenSafetyCommands)
            }

            FamilySetupSection(title = "4. 检查权限与播报") {
                Text(
                    "检查麦克风、通知、省电和无障碍状态，并为老人调整字号、对比度、离线播报语速和音量。",
                    style = MaterialTheme.typography.bodyLarge,
                )
                FamilyActionButton("打开设置与帮助", onOpenSettings)
            }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                onClick = onBack,
            ) {
                Text("完成并返回首页")
            }
        }
    }
}

@Composable
private fun FamilyInvitationActions(
    state: InvitationUiState,
    onCreateInvitation: () -> Unit,
    onRefreshInvitations: () -> Unit,
    onShareInvitation: (String) -> Unit,
    onRevokeInvitation: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state) {
            InvitationUiState.Idle,
            InvitationUiState.Revoked
            -> FamilyActionButton("创建亲友邀请", onCreateInvitation)

            InvitationUiState.Restoring -> {
                CircularProgressIndicator()
                Text("正在读取待处理邀请", style = MaterialTheme.typography.bodyLarge)
            }

            is InvitationUiState.Recovered -> RestoredInvitationActions(
                invitations = state.invitations,
                onRevokeInvitation = onRevokeInvitation,
            )

            InvitationUiState.Creating -> {
                CircularProgressIndicator()
                Text("正在创建邀请", style = MaterialTheme.typography.bodyLarge)
            }

            is InvitationUiState.Active -> {
                Text("邀请已创建，24 小时内有效。", style = MaterialTheme.typography.bodyLarge)
                FamilyActionButton("打开微信分享邀请") {
                    routeInvitationShare(
                        invitation = state.invitation,
                        onShare = onShareInvitation,
                        onRefresh = onRefreshInvitations,
                    )
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    onClick = { onRevokeInvitation(state.invitation.invitationId) },
                ) { Text("撤销本次邀请") }
            }

            is InvitationUiState.Revoking -> {
                CircularProgressIndicator()
                Text("正在撤销邀请", style = MaterialTheme.typography.bodyLarge)
            }

            is InvitationUiState.RestoreError -> {
                Text(
                    state.message.toChineseUiMessage("待处理邀请暂时无法读取。"),
                    style = MaterialTheme.typography.bodyLarge,
                )
                FamilyActionButton("重新读取邀请", onRefreshInvitations)
            }

            is InvitationUiState.Error -> {
                Text(state.message.toChineseUiMessage("操作没有完成，请稍后重试。"), style = MaterialTheme.typography.bodyLarge)
                if (state.recoveredInvitations.isNotEmpty()) {
                    RestoredInvitationActions(
                        invitations = state.recoveredInvitations,
                        onRevokeInvitation = onRevokeInvitation,
                    )
                } else if (state.invitation == null) {
                    FamilyActionButton("重新创建邀请", onCreateInvitation)
                } else {
                    FamilyActionButton("重新选择微信分享") {
                        routeInvitationShare(
                            invitation = state.invitation,
                            onShare = onShareInvitation,
                            onRefresh = onRefreshInvitations,
                        )
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        onClick = { onRevokeInvitation(state.invitation.invitationId) },
                    ) { Text("撤销本次邀请") }
                }
            }
        }
    }
}

@Composable
private fun RestoredInvitationActions(
    invitations: List<RestoredInvitation>,
    onRevokeInvitation: (String) -> Unit,
) {
    Text(
        "发现 ${invitations.size} 个等待亲友确认的邀请。旧分享链接不会保存在本机。",
        style = MaterialTheme.typography.bodyLarge,
    )
    invitations.forEachIndexed { index, invitation ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("待处理邀请 ${index + 1}", style = MaterialTheme.typography.titleLarge)
                Text("等待亲友确认", style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    onClick = { onRevokeInvitation(invitation.invitationId) },
                ) {
                    Text("撤销待处理邀请 ${index + 1}")
                }
            }
        }
    }
    Text(
        "如需重新分享，请先撤销旧邀请，再创建新邀请。",
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun FamilySetupSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
            )
            content()
        }
    }
}

@Composable
private fun FamilyActionButton(
    text: String,
    onClick: () -> Unit,
) {
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        onClick = onClick,
    ) {
        Text(text)
    }
}
