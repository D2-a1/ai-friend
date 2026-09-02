package com.aifriend.feature.guardian

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aifriend.app.ui.components.MicrophonePermissionRecoveryCard
import com.aifriend.app.ui.components.openApplicationDetailsSettings
import com.aifriend.core.design.AccessibleStatusIndicator

/**
 * 首页守护控制入口。权限请求和服务启动只由当前可见页面的点击触发。
 */
@Composable
fun GuardianControlRoute(
    status: GuardianStatus,
    wakeReadiness: GuardianWakeReadiness,
    onPermissionDenied: () -> Unit,
    onWakeUnavailable: () -> Unit,
) {
    val context = LocalContext.current
    val requiredPermissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (requiredPermissions.all { results[it] == true }) {
            GuardianServiceController.startFromVisiblePage(context)
        } else {
            onPermissionDenied()
        }
    }
    GuardianControl(
        status = status,
        onEnable = {
            if (wakeReadiness != GuardianWakeReadiness.READY) {
                onWakeUnavailable()
                return@GuardianControl
            }
            val missing = requiredPermissions.filter { permission ->
                ContextCompat.checkSelfPermission(context, permission) !=
                    PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) {
                GuardianServiceController.startFromVisiblePage(context)
            } else {
                permissionLauncher.launch(missing.toTypedArray())
            }
        },
        onDisable = { GuardianServiceController.stop(context) },
        onOpenAppPermissionSettings = { context.openApplicationDetailsSettings() },
    )
}

/** 不持有 Context 的适老化守护状态卡片。 */
@Composable
fun GuardianControl(
    status: GuardianStatus,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onOpenAppPermissionSettings: () -> Unit = {},
) {
    var disableConfirmationRequested by rememberSaveable(status.active) {
        mutableStateOf(false)
    }
    if (disableConfirmationRequested) {
        AlertDialog(
            onDismissRequest = {
                disableConfirmationRequested = resolveGuardianDisableConfirmation(
                    disableConfirmationRequested,
                    GuardianDisableConfirmationAction.CANCEL,
                ).confirmationRequested
            },
            title = { Text("确认关闭小友守护？") },
            text = { Text("关闭后，小友不会再等待您说“小友、小友”。") },
            confirmButton = {
                Button(
                    onClick = {
                        val decision = resolveGuardianDisableConfirmation(
                            disableConfirmationRequested,
                            GuardianDisableConfirmationAction.CONFIRM,
                        )
                        disableConfirmationRequested = decision.confirmationRequested
                        if (decision.disableRequested) onDisable()
                    },
                ) {
                    Text("确认关闭")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        disableConfirmationRequested = resolveGuardianDisableConfirmation(
                            disableConfirmationRequested,
                            GuardianDisableConfirmationAction.CANCEL,
                        ).confirmationRequested
                    },
                ) {
                    Text("继续守护")
                }
            },
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (status.active) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "小友守护",
                style = MaterialTheme.typography.headlineSmall,
            )
            AccessibleStatusIndicator(
                presentation = status.feedbackPresentation(),
                message = status.message,
                contextLabel = "小友守护状态",
            )
            if (status.permissionRecoveryRequired) {
                MicrophonePermissionRecoveryCard(
                    onOpenAppPermissionSettings = onOpenAppPermissionSettings,
                    message = "请在系统设置中允许小友使用麦克风；如果通知已关闭，也请一并开启。返回后再重新开启守护。",
                    actionLabel = "打开应用权限设置",
                )
            }
            if (status.active) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    onClick = {
                        disableConfirmationRequested = resolveGuardianDisableConfirmation(
                            disableConfirmationRequested,
                            GuardianDisableConfirmationAction.REQUEST,
                        ).confirmationRequested
                    },
                ) {
                    Text("关闭小友守护", style = MaterialTheme.typography.titleLarge)
                }
            } else {
                Button(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    onClick = onEnable,
                ) {
                    Text(
                        when {
                            status.permissionRecoveryRequired -> "检查权限并重新开启"
                            status.mode == GuardianMode.ERROR -> "重新检查并开启"
                            else -> "开启小友守护"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        }
    }
}

/** 守护关闭确认只允许由“请求确认→明确确认”两步产生停止动作。 */
internal enum class GuardianDisableConfirmationAction {
    REQUEST,
    CANCEL,
    CONFIRM,
}

/** 守护关闭确认的纯状态结果，便于在不启动设备的情况下验证业务门禁。 */
internal data class GuardianDisableConfirmationDecision(
    val confirmationRequested: Boolean,
    val disableRequested: Boolean,
)

/** 关闭守护确认状态转换；没有待确认状态时，确认动作不得直接停止守护。 */
internal fun resolveGuardianDisableConfirmation(
    confirmationRequested: Boolean,
    action: GuardianDisableConfirmationAction,
): GuardianDisableConfirmationDecision = when (action) {
    GuardianDisableConfirmationAction.REQUEST -> GuardianDisableConfirmationDecision(
        confirmationRequested = true,
        disableRequested = false,
    )

    GuardianDisableConfirmationAction.CANCEL -> GuardianDisableConfirmationDecision(
        confirmationRequested = false,
        disableRequested = false,
    )

    GuardianDisableConfirmationAction.CONFIRM -> GuardianDisableConfirmationDecision(
        confirmationRequested = false,
        disableRequested = confirmationRequested,
    )
}
