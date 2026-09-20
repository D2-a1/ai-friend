package com.aifriend.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aifriend.app.ui.components.BrandHero
import com.aifriend.app.ui.components.ElderPage
import com.aifriend.app.ui.components.InfoPanel
import com.aifriend.app.ui.components.SectionCard
import com.aifriend.feature.settings.CapabilityReadState
import com.aifriend.feature.settings.SettingsCapabilityStatus
import com.aifriend.feature.guardian.GuardianControlRoute
import com.aifriend.feature.guardian.GuardianMode
import com.aifriend.feature.guardian.GuardianStatus
import com.aifriend.feature.guardian.GuardianWakeReadiness
import com.aifriend.contract.model.ContactStatus
import com.aifriend.feature.contact.ui.ContactManagementUiState
import com.aifriend.feature.contact.ui.debugDemoReadiness
import com.aifriend.feature.contact.ui.userFacingLabel

/** 首页只保留最高频的联系任务和守护状态。 */
@Composable
internal fun HomeScreen(
    guardianStatus: GuardianStatus,
    guardianWakeReadiness: GuardianWakeReadiness,
    capabilityStatus: SettingsCapabilityStatus?,
    contactState: ContactManagementUiState,
    demoEnabled: Boolean,
    onStartTask: () -> Unit,
    onOpenDemoSetup: () -> Unit,
    onOpenContacts: () -> Unit,
    onGuardianPermissionDenied: () -> Unit,
    onGuardianWakeUnavailable: () -> Unit,
) {
    ElderPage {
        BrandHero(compact = true)
        HomeTaskCard(
            presentation = contactState.homeTaskPresentation(demoEnabled),
            onStartTask = onStartTask,
            onOpenDemoSetup = onOpenDemoSetup,
        )
        HomeCapabilitySummary(
            items = homeCapabilityItems(capabilityStatus, guardianStatus),
        )
        SectionCard(
            title = "语音守护",
            support = "打开后，说“小友”就能开始联系家人。",
        ) {
            GuardianControlRoute(
                status = guardianStatus,
                wakeReadiness = guardianWakeReadiness,
                onPermissionDenied = onGuardianPermissionDenied,
                onWakeUnavailable = onGuardianWakeUnavailable,
            )
        }
        HomeContactShortcuts(
            shortcuts = contactState.homeContactShortcuts(),
            onOpenContacts = onOpenContacts,
        )
        InfoPanel("每次联系前都会完整复述联系人和动作，只有您明确确认后才会继续。")
    }
}

@Composable
private fun HomeCapabilitySummary(items: List<HomeCapabilityItem>) {
    SectionCard(
        title = "当前状态",
        support = "这里只显示手机当前状态，不代表微信联系已经可用。",
    ) {
        items.forEach { item ->
            val colors = when (item.state) {
                CapabilityReadState.AVAILABLE -> MaterialTheme.colorScheme.primaryContainer to
                    MaterialTheme.colorScheme.onPrimaryContainer
                CapabilityReadState.UNAVAILABLE -> MaterialTheme.colorScheme.errorContainer to
                    MaterialTheme.colorScheme.onErrorContainer
                CapabilityReadState.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant to
                    MaterialTheme.colorScheme.onSurfaceVariant
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${item.title}，${item.detail}"
                    },
                shape = MaterialTheme.shapes.medium,
                color = colors.first,
                contentColor = colors.second,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when (item.state) {
                            CapabilityReadState.AVAILABLE -> "✓"
                            CapabilityReadState.UNAVAILABLE -> "!"
                            CapabilityReadState.UNKNOWN -> "?"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Column {
                        Text(item.title, style = MaterialTheme.typography.titleLarge)
                        Text(item.detail, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

/** 首页只读状态项；不把权限或辅助服务状态推导为微信执行能力。 */
internal data class HomeCapabilityItem(
    val title: String,
    val detail: String,
    val state: CapabilityReadState,
)

/** 把同一设备事实转换为首页有限中文状态。 */
internal fun homeCapabilityItems(
    capabilities: SettingsCapabilityStatus?,
    guardianStatus: GuardianStatus,
): List<HomeCapabilityItem> = listOf(
    capabilityItem(
        title = "网络",
        state = capabilities?.network,
        available = "网络可用",
        unavailable = "网络不可用",
        unknown = "网络状态未知",
    ),
    capabilityItem(
        title = "麦克风",
        state = capabilities?.microphone,
        available = "麦克风已允许",
        unavailable = "麦克风未允许",
        unknown = "麦克风状态未知",
    ),
    capabilityItem(
        title = "微信辅助",
        state = capabilities?.restrictedWechatAccessibility,
        available = "辅助服务已开启",
        unavailable = "辅助服务未开启",
        unknown = "辅助服务状态未知",
    ),
    guardianCapabilityItem(guardianStatus),
)

private fun capabilityItem(
    title: String,
    state: CapabilityReadState?,
    available: String,
    unavailable: String,
    unknown: String,
): HomeCapabilityItem = when (state) {
    CapabilityReadState.AVAILABLE -> HomeCapabilityItem(title, available, state)
    CapabilityReadState.UNAVAILABLE -> HomeCapabilityItem(title, unavailable, state)
    CapabilityReadState.UNKNOWN,
    null,
    -> HomeCapabilityItem(title, unknown, CapabilityReadState.UNKNOWN)
}

private fun guardianCapabilityItem(status: GuardianStatus): HomeCapabilityItem = when (status.mode) {
    GuardianMode.STARTING -> HomeCapabilityItem(
        title = "小友守护",
        detail = "正在开启守护",
        state = CapabilityReadState.UNKNOWN,
    )
    GuardianMode.SLEEPING,
    GuardianMode.AWAKE_LISTENING,
    GuardianMode.PROCESSING,
    GuardianMode.TASK_HANDOFF,
    GuardianMode.QUESTION_PAUSED,
    GuardianMode.WECHAT_BUSY,
    -> HomeCapabilityItem(
        title = "小友守护",
        detail = "守护已开启",
        state = CapabilityReadState.AVAILABLE,
    )
    GuardianMode.GUARDIAN_OFF,
    GuardianMode.ERROR,
    -> HomeCapabilityItem(
        title = "小友守护",
        detail = "守护未开启",
        state = CapabilityReadState.UNAVAILABLE,
    )
}

@Composable
private fun HomeContactShortcuts(
    shortcuts: List<HomeContactShortcut>,
    onOpenContacts: () -> Unit,
) {
    if (shortcuts.isEmpty()) return
    SectionCard(
        title = "亲友快捷入口",
        support = "显示最近更新的可联系亲友，最多四位。",
    ) {
        shortcuts.chunked(2).forEach { rowShortcuts ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowShortcuts.forEach { shortcut ->
                    Surface(
                        onClick = onOpenContacts,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 126.dp)
                            .semantics {
                                contentDescription = "打开亲友管理，查看${shortcut.label}"
                            },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Surface(
                                modifier = Modifier.size(64.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = shortcut.avatarText,
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            Text(
                                text = shortcut.label,
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (rowShortcuts.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** 首页只使用联系人列表已有顺序，不伪造使用频率或自动选择联系人。 */
internal data class HomeContactShortcut(
    val contactId: String,
    val label: String,
    val avatarText: String,
)

/** 只展示最多四位当前可联系亲友；其他状态继续由联系人管理页处理。 */
internal fun ContactManagementUiState.homeContactShortcuts(): List<HomeContactShortcut> = contacts
    .asSequence()
    .filter { it.status == ContactStatus.ACTIVE }
    .take(MAX_HOME_CONTACT_SHORTCUTS)
    .map { contact ->
        val label = contact.userFacingLabel()
        HomeContactShortcut(
            contactId = contact.id,
            label = label,
            avatarText = label.first().toString(),
        )
    }
    .toList()

@Composable
private fun HomeTaskCard(
    presentation: HomeTaskPresentation,
    onStartTask: () -> Unit,
    onOpenDemoSetup: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = presentation.title, style = MaterialTheme.typography.headlineSmall)
            Text(
                text = presentation.support,
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(
                modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp),
                enabled = presentation.action != HomeTaskAction.NONE,
                onClick = {
                    when (presentation.action) {
                        HomeTaskAction.START_TASK -> onStartTask()
                        HomeTaskAction.OPEN_DEMO_SETUP -> onOpenDemoSetup()
                        HomeTaskAction.NONE -> Unit
                    }
                },
            ) {
                Text(presentation.actionLabel, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

/** 首页最高频任务卡片的非敏感展示状态。 */
internal data class HomeTaskPresentation(
    val title: String,
    val support: String,
    val actionLabel: String,
    val action: HomeTaskAction,
)

/** 首页任务卡片只允许启动任务、打开既有准备页或保持禁用。 */
internal enum class HomeTaskAction {
    START_TASK,
    OPEN_DEMO_SETUP,
    NONE,
}

/**
 * 将既有 Debug 四步门禁折叠为首页单一下一步，不读取联系人名称或微信资料。
 */
internal fun ContactManagementUiState.homeTaskPresentation(
    demoEnabled: Boolean,
): HomeTaskPresentation {
    if (!demoEnabled) {
        return HomeTaskPresentation(
            title = "想联系家人？",
            support = "点一下，然后直接说要联系谁、做什么。",
            actionLabel = "开始说话",
            action = HomeTaskAction.START_TASK,
        )
    }
    if (isInitialLoading || isCheckingDemoReadiness) {
        return HomeTaskPresentation(
            title = "正在检查体验准备",
            support = "请稍候，检查完成后会告诉您下一步。",
            actionLabel = "正在检查",
            action = HomeTaskAction.NONE,
        )
    }
    if (isRefreshing || isPreparingDemoContact) {
        return HomeTaskPresentation(
            title = "正在更新体验准备",
            support = "请稍候，更新完成后会告诉您下一步。",
            actionLabel = "正在更新",
            action = HomeTaskAction.NONE,
        )
    }
    if (contacts.isEmpty() && errorMessage != null) {
        return HomeTaskPresentation(
            title = "体验准备暂时无法继续",
            support = "请打开准备步骤，查看提示后重新操作。",
            actionLabel = "查看准备步骤",
            action = HomeTaskAction.OPEN_DEMO_SETUP,
        )
    }
    if (demoReadinessCheckFailed) {
        return HomeTaskPresentation(
            title = "体验准备暂时无法确认",
            support = "请打开准备步骤，重新检查安全指令状态。",
            actionLabel = "查看准备步骤",
            action = HomeTaskAction.OPEN_DEMO_SETUP,
        )
    }

    val readiness = debugDemoReadiness()
    return when {
        readiness.demoContact == null -> HomeTaskPresentation(
            title = "先准备体验联系人",
            support = "完成联系人、称呼和安全指令后，才能体验完整任务。",
            actionLabel = "查看准备步骤",
            action = HomeTaskAction.OPEN_DEMO_SETUP,
        )

        !readiness.aliasReady -> HomeTaskPresentation(
            title = "下一步设置体验称呼",
            support = "给体验联系人录制一个平时习惯使用的称呼。",
            actionLabel = "继续准备",
            action = HomeTaskAction.OPEN_DEMO_SETUP,
        )

        !readiness.safetyCommandsReady -> HomeTaskPresentation(
            title = "下一步录制安全指令",
            support = "录完发送、拨打、取消、重说四类动作指令后，就能开始体验任务。",
            actionLabel = "继续准备",
            action = HomeTaskAction.OPEN_DEMO_SETUP,
        )

        else -> HomeTaskPresentation(
            title = "可以开始体验任务",
            support = "点一下，然后直接说要联系谁、做什么。最后只会模拟完成。",
            actionLabel = "开始体验任务",
            action = HomeTaskAction.START_TASK,
        )
    }
}

private const val MAX_HOME_CONTACT_SHORTCUTS = 4
