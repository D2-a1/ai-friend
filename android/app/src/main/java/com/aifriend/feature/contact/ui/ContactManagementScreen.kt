package com.aifriend.feature.contact.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aifriend.contract.model.Contact
import com.aifriend.contract.model.ContactStatus

internal const val DEBUG_DEMO_RELATIONSHIP = "仅用于本机体验"

/**
 * 联系人管理页面。只展示当前 owner 的最小联系人数据，不展示微信主体或稳定定位。
 *
 * @author codex
 * @since 2026-08-09
 */
@Composable
fun ContactManagementScreen(
    state: ContactManagementUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onManageAliases: (Contact) -> Unit,
    onRequestUnbind: (String) -> Unit,
    onContinueUnbind: () -> Unit,
    onConfirmUnbind: () -> Unit,
    onCancelUnbind: () -> Unit,
    onDismissMessage: () -> Unit,
    onStartLocalVerification: (String) -> Unit = {},
    onOpenWechatForVerification: () -> Unit = {},
    onOpenAccessibilitySettings: () -> Unit = {},
    onConfirmLocalVerification: () -> Unit = {},
    onCancelLocalVerification: () -> Unit = {},
    demoEnabled: Boolean = false,
    onPrepareDemoContact: () -> Unit = {},
    onRefreshDemoReadiness: () -> Unit = {},
    onOpenSafetyCommands: () -> Unit = {},
    onStartTask: () -> Unit = {},
) {
    BackHandler(enabled = state.localVerification != null) {
        onCancelLocalVerification()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = if (state.localVerification == null) "联系人管理" else "本机验证",
            style = MaterialTheme.typography.headlineLarge,
        )
        if (state.localVerification != null) {
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                enabled = !state.localVerification.isSubmitting,
                onClick = onCancelLocalVerification,
            ) {
                Text("返回联系人", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp),
                    onClick = onBack,
                ) {
                    Text("返回首页", style = MaterialTheme.typography.bodyLarge)
                }
                Button(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp),
                    enabled = !state.isInitialLoading && !state.isRefreshing &&
                        state.unbindingContactId == null,
                    onClick = onRefresh,
                ) {
                    Text("刷新", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        state.errorMessage?.let { message ->
            MessageCard(
                message = message,
                isError = true,
                onDismiss = onDismissMessage,
            )
        }
        state.informationMessage?.let { message ->
            MessageCard(
                message = message,
                isError = false,
                onDismiss = onDismissMessage,
            )
        }

        if (demoEnabled && state.localVerification == null) {
            val readiness = state.debugDemoReadiness()
            DebugDemoCard(
                isPreparing = state.isPreparingDemoContact,
                isContactStateUpdating = state.isInitialLoading || state.isRefreshing,
                readiness = readiness,
                isCheckingReadiness = state.isCheckingDemoReadiness,
                readinessCheckFailed = state.demoReadinessCheckFailed,
                onPrepareDemoContact = onPrepareDemoContact,
                onManageAliases = onManageAliases,
                onRefreshDemoReadiness = onRefreshDemoReadiness,
                onOpenSafetyCommands = onOpenSafetyCommands,
                onStartTask = onStartTask,
            )
        }

        val localVerification = state.localVerification
        if (localVerification != null) {
            LocalVerificationContent(
                modifier = Modifier.weight(1f),
                verification = localVerification,
                onOpenWechat = onOpenWechatForVerification,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onConfirm = onConfirmLocalVerification,
            )
        } else {
            when {
                state.isInitialLoading -> LoadingContent()
                state.contacts.isEmpty() && state.errorMessage != null -> RetryContent(
                    onRefresh = onRefresh,
                )
                state.contacts.isEmpty() -> EmptyContent(onRefresh = onRefresh)
                else -> ContactList(
                    modifier = Modifier.weight(1f),
                    contacts = state.contacts,
                    unbindingContactId = state.unbindingContactId,
                    isRefreshing = state.isRefreshing,
                    onManageAliases = onManageAliases,
                    onStartLocalVerification = onStartLocalVerification,
                    onRequestUnbind = onRequestUnbind,
                )
            }
        }
    }

    state.pendingUnbind?.let { pending ->
        ContactUnbindDialog(
            pending = pending,
            onContinue = onContinueUnbind,
            onConfirm = onConfirmUnbind,
            onCancel = onCancelUnbind,
        )
    }
}

@Composable
private fun LocalVerificationContent(
    modifier: Modifier,
    verification: PendingContactLocalVerification,
    onOpenWechat: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "验证${verification.contactLabel}",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = "只需一次。点击后在微信中进入这位亲友的资料页，看到“微信号”和“发消息”后停留片刻，再返回小友。被邀请人无需安装小友。",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "只在本次一分钟窗口内读取用于验证的微信号字段和固定按钮；不读取聊天内容，不截图，不点击，也不保存到本机。",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "已完成 ${verification.capture.completedObservations} / 1 次",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = verification.capture.message,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = if (verification.capture.accessibilityReady) {
                        "微信辅助服务：已连接"
                    } else {
                        "微信辅助服务：未连接"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                verification.capture.wechatVersion?.let { version ->
                    Text(
                        text = "当前微信版本：$version",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (verification.capture.ready) {
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        enabled = !verification.isSubmitting,
                        onClick = onConfirm,
                    ) {
                        Text(
                            text = if (verification.isSubmitting) {
                                "正在完成本机验证"
                            } else {
                                "确认完成本机验证"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else {
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        enabled = verification.capture.accessibilityReady &&
                            !verification.capture.awaiting && !verification.isSubmitting,
                        onClick = onOpenWechat,
                    ) {
                        Text(
                            text = if (!verification.capture.accessibilityReady) {
                                "请先开启微信辅助服务"
                            } else if (verification.capture.awaiting) {
                                "正在等待联系人资料页"
                            } else {
                                "打开微信完成第 ${verification.capture.completedObservations + 1} 次"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                OutlinedButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    enabled = !verification.isSubmitting,
                    onClick = onOpenAccessibilitySettings,
                ) {
                    Text("检查微信辅助服务", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun DebugDemoCard(
    isPreparing: Boolean,
    isContactStateUpdating: Boolean,
    readiness: DebugDemoReadiness,
    isCheckingReadiness: Boolean,
    readinessCheckFailed: Boolean,
    onPrepareDemoContact: () -> Unit,
    onManageAliases: (Contact) -> Unit,
    onRefreshDemoReadiness: () -> Unit,
    onOpenSafetyCommands: () -> Unit,
    onStartTask: () -> Unit,
) {
    val actions = readiness.actionAvailability(
        isContactStateUpdating = isContactStateUpdating,
        isPreparing = isPreparing,
        isCheckingReadiness = isCheckingReadiness,
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("个人体验流程", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "只用于测试：按顺序完成联系人、称呼和安全指令后，才能开始完整任务。最后只显示模拟完成，不会调用微信。",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = readiness.statusText(
                    isCheckingReadiness = isCheckingReadiness,
                    readinessCheckFailed = readinessCheckFailed,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                enabled = actions.canPrepareContact,
                onClick = onPrepareDemoContact,
            ) {
                Text(
                    text = when {
                        isPreparing -> "正在准备体验联系人"
                        readiness.demoContact != null -> "体验联系人已准备"
                        else -> "准备体验联系人"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                enabled = actions.canManageAlias,
                onClick = {
                    readiness.demoContact?.let(onManageAliases)
                },
            ) {
                Text(
                    text = when {
                        readiness.aliasReady -> "体验称呼已准备"
                        readiness.demoContact == null -> "先准备体验联系人"
                        else -> "设置体验称呼"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                enabled = actions.canManageSafetyCommands,
                onClick = if (readinessCheckFailed) {
                    onRefreshDemoReadiness
                } else {
                    onOpenSafetyCommands
                },
            ) {
                Text(
                    text = when {
                        isCheckingReadiness -> "正在检查安全指令"
                        readiness.safetyCommandsReady -> "安全指令已准备"
                        readinessCheckFailed -> "重新检查安全指令"
                        !readiness.aliasReady -> "先设置体验称呼"
                        else -> "录制安全指令"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                enabled = actions.canStartTask,
                onClick = onStartTask,
            ) {
                Text("开始体验任务", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Debug 体验流程的非敏感就绪摘要。 */
internal data class DebugDemoReadiness(
    val demoContact: Contact?,
    val aliasReady: Boolean,
    val safetyCommandsReady: Boolean,
) {
    val taskReady: Boolean = aliasReady && safetyCommandsReady

    fun statusText(
        isCheckingReadiness: Boolean,
        readinessCheckFailed: Boolean,
    ): String = buildString {
        append(if (demoContact == null) "第一步：准备体验联系人" else "第一步：体验联系人已完成")
        append('\n')
        append(if (aliasReady) "第二步：体验称呼已完成" else "第二步：设置体验称呼")
        append('\n')
        append(
            when {
                isCheckingReadiness -> "第三步：正在检查安全指令"
                readinessCheckFailed -> "第三步：安全指令状态暂时无法确认"
                safetyCommandsReady -> "第三步：安全指令已完成"
                else -> "第三步：录制四类安全指令"
            },
        )
        append('\n')
        append(if (taskReady) "第四步：可以开始体验任务" else "第四步：完成前三步后开始任务")
    }
}

/** 从当前页面状态生成体验流程门禁，不读取联系人名称或微信资料。 */
internal fun ContactManagementUiState.debugDemoReadiness(): DebugDemoReadiness {
    val demoContact = contacts.singleOrNull {
        it.relationship == DEBUG_DEMO_RELATIONSHIP && it.status != ContactStatus.REVOKED
    }
    val aliasReady = demoContact?.let {
        it.status == ContactStatus.ACTIVE && it.aliasCount > 0
    } == true
    return DebugDemoReadiness(
        demoContact = demoContact,
        aliasReady = aliasReady,
        safetyCommandsReady = safetyCommandsReady,
    )
}

/** Debug 四步卡片在当前状态下唯一允许的显式动作。 */
internal data class DebugDemoActionAvailability(
    val canPrepareContact: Boolean,
    val canManageAlias: Boolean,
    val canManageSafetyCommands: Boolean,
    val canStartTask: Boolean,
)

/** 联系人状态变化期间关闭全部入口，避免用户点击后被 ViewModel 静默拒绝。 */
internal fun DebugDemoReadiness.actionAvailability(
    isContactStateUpdating: Boolean,
    isPreparing: Boolean,
    isCheckingReadiness: Boolean,
): DebugDemoActionAvailability {
    val busy = isContactStateUpdating || isPreparing || isCheckingReadiness
    return DebugDemoActionAvailability(
        canPrepareContact = !busy && demoContact == null,
        canManageAlias = !busy && demoContact != null && !aliasReady,
        canManageSafetyCommands = !busy && aliasReady && !safetyCommandsReady,
        canStartTask = !busy && taskReady,
    )
}

@Composable
private fun LoadingContent() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.semantics { contentDescription = "正在加载联系人" },
        )
        Text("正在加载联系人", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun EmptyContent(onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("还没有已绑定的亲友", style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "可返回首页创建邀请。亲友同意后，联系人会显示在这里。",
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedButton(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            onClick = onRefresh,
        ) {
            Text("重新加载", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun RetryContent(onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "联系人暂时无法加载，请检查网络后重试。",
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            onClick = onRefresh,
        ) {
            Text("重试加载", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ContactList(
    modifier: Modifier,
    contacts: List<Contact>,
    unbindingContactId: String?,
    isRefreshing: Boolean,
    onManageAliases: (Contact) -> Unit,
    onStartLocalVerification: (String) -> Unit,
    onRequestUnbind: (String) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (isRefreshing) {
            item {
                Text("正在刷新联系人", style = MaterialTheme.typography.bodyLarge)
            }
        }
        items(
            items = contacts,
            key = Contact::id,
        ) { contact ->
            ContactCard(
                contact = contact,
                isUnbinding = unbindingContactId == contact.id,
                actionsEnabled = unbindingContactId == null && !isRefreshing,
                onManageAliases = onManageAliases,
                onStartLocalVerification = onStartLocalVerification,
                onRequestUnbind = onRequestUnbind,
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ContactCard(
    contact: Contact,
    isUnbinding: Boolean,
    actionsEnabled: Boolean,
    onManageAliases: (Contact) -> Unit,
    onStartLocalVerification: (String) -> Unit,
    onRequestUnbind: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = contact.userFacingLabel(),
                style = MaterialTheme.typography.headlineSmall,
            )
            contact.relationship?.takeIf { it.isNotBlank() }?.let {
                Text("关系：$it", style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                text = "状态：${contact.status.displayText()}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = if (contact.aliasCount == 0) {
                    "称呼：未设置称呼"
                } else {
                    "称呼：${contact.aliasCount} 个"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            val aliasStatusAllowed = contact.status in setOf(
                ContactStatus.ACTIVE_NO_ALIAS,
                ContactStatus.ACTIVE,
            )
            if (contact.status in setOf(
                    ContactStatus.PENDING_LOCAL_VERIFY,
                    ContactStatus.REVERIFY_REQUIRED,
                )
            ) {
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    enabled = actionsEnabled,
                    onClick = { onStartLocalVerification(contact.id) },
                ) {
                    Text(
                        text = if (contact.status == ContactStatus.REVERIFY_REQUIRED) {
                            "重新完成本机验证"
                        } else {
                            "完成本机验证"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                enabled = actionsEnabled && aliasStatusAllowed && contact.aliasCount < 5,
                onClick = { onManageAliases(contact) },
            ) {
                Text(
                    text = when {
                        contact.aliasCount >= 5 -> "称呼已满"
                        contact.aliasCount == 0 -> "设置称呼"
                        else -> "新增称呼"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (contact.relationship != DEBUG_DEMO_RELATIONSHIP) {
                OutlinedButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    enabled = actionsEnabled,
                    onClick = { onRequestUnbind(contact.id) },
                ) {
                    Text(
                        text = if (isUnbinding) "正在解除绑定" else "解除绑定",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageCard(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (isError) "操作未完成：$message" else message,
                style = MaterialTheme.typography.bodyLarge,
            )
            TextButton(
                modifier = Modifier.heightIn(min = 56.dp),
                onClick = onDismiss,
            ) {
                Text("知道了", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun ContactUnbindDialog(
    pending: PendingContactUnbind,
    onContinue: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val isFinal = pending.stage == ContactUnbindConfirmationStage.FINAL_CONFIRMATION
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                text = if (isFinal) "再次确认解除绑定" else "要解除绑定吗？",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Text(
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
                text = if (isFinal) {
                    "确认解除与${pending.contactLabel}的绑定。解除后如需恢复，亲友必须重新同意邀请，并再次由老人手机确认联系人。"
                } else {
                    "解除后，小友将不能再通过微信联系${pending.contactLabel}，相关联系人定位也会立即失效。"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        confirmButton = {
            Button(
                modifier = Modifier.heightIn(min = 56.dp),
                onClick = if (isFinal) onConfirm else onContinue,
            ) {
                Text(
                    text = if (isFinal) "确认解除绑定" else "继续",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                modifier = Modifier.heightIn(min = 56.dp),
                onClick = onCancel,
            ) {
                Text("取消", style = MaterialTheme.typography.bodyLarge)
            }
        },
    )
}

private fun ContactStatus.displayText(): String = when (this) {
    ContactStatus.PENDING_CONSENT -> "等待亲友确认"
    ContactStatus.PENDING_LOCAL_VERIFY -> "等待老人手机确认"
    ContactStatus.ACTIVE_NO_ALIAS -> "已验证，未设置称呼"
    ContactStatus.ACTIVE -> "可使用"
    ContactStatus.REVERIFY_REQUIRED -> "需要重新验证"
    ContactStatus.REVOKED -> "已解除绑定"
    ContactStatus.BLOCKED -> "已暂停使用"
}
