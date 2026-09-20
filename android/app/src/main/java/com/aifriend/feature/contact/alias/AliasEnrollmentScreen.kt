package com.aifriend.feature.contact.alias

import com.aifriend.app.ui.components.toChineseUiMessage

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aifriend.app.ui.components.MicrophonePermissionRecoveryCard
import com.aifriend.app.ui.components.openApplicationDetailsSettings

/**
 * 带麦克风运行时权限处理的称呼双录页面入口。
 *
 * @author codex
 * @since 2026-08-12
 */
@Composable
fun AliasEnrollmentRoute(
    state: AliasEnrollmentUiState,
    onBack: () -> Unit,
    onDisplayTextChanged: (String) -> Unit,
    onGrantConsent: () -> Unit,
    onStartRecording: () -> Unit,
    onFinishRecording: () -> Unit,
    onPermissionDenied: () -> Unit,
    onPlay: (AliasRecordingSlot) -> Unit,
    onRetake: (AliasRecordingSlot) -> Unit,
    onConfirmAndSubmit: () -> Unit,
    onContinueAfterCompletion: () -> Unit,
    onRequestAliasDeletion: (String) -> Unit,
    onCancelAliasDeletion: () -> Unit,
    onConfirmAliasDeletion: () -> Unit,
    onDismissError: () -> Unit,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) onStartRecording() else onPermissionDenied()
    }
    AliasEnrollmentScreen(
        state = state,
        onBack = onBack,
        onDisplayTextChanged = onDisplayTextChanged,
        onGrantConsent = onGrantConsent,
        onRequestRecording = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                onStartRecording()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onFinishRecording = onFinishRecording,
        onPlay = onPlay,
        onRetake = onRetake,
        onConfirmAndSubmit = onConfirmAndSubmit,
        onContinueAfterCompletion = onContinueAfterCompletion,
        onRequestAliasDeletion = onRequestAliasDeletion,
        onCancelAliasDeletion = onCancelAliasDeletion,
        onConfirmAliasDeletion = onConfirmAliasDeletion,
        onOpenAppPermissionSettings = { context.openApplicationDetailsSettings() },
        onDismissError = onDismissError,
    )
}

/**
 * 称呼双录页面。页面只展示时长和状态，不持有原始音频。
 *
 * @author codex
 * @since 2026-08-12
 */
@Composable
fun AliasEnrollmentScreen(
    state: AliasEnrollmentUiState,
    onBack: () -> Unit,
    onDisplayTextChanged: (String) -> Unit,
    onGrantConsent: () -> Unit = {},
    onRequestRecording: () -> Unit,
    onFinishRecording: () -> Unit,
    onPlay: (AliasRecordingSlot) -> Unit,
    onRetake: (AliasRecordingSlot) -> Unit,
    onConfirmAndSubmit: () -> Unit,
    onContinueAfterCompletion: () -> Unit = {},
    onRequestAliasDeletion: (String) -> Unit = {},
    onCancelAliasDeletion: () -> Unit = {},
    onConfirmAliasDeletion: () -> Unit = {},
    onOpenAppPermissionSettings: () -> Unit = {},
    onDismissError: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "录制联系人称呼",
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            text = if (state.contactLabel.isBlank()) {
                "正在读取联系人"
            } else {
                "联系人：${state.contactLabel}"
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        OutlinedButton(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            onClick = onBack,
        ) {
            Text("取消并返回", style = MaterialTheme.typography.bodyLarge)
        }

        state.errorMessage?.let { message ->
            ErrorCard(message = message, onDismiss = onDismissError)
        }
        if (state.microphonePermissionRecoveryRequired) {
            MicrophonePermissionRecoveryCard(onOpenAppPermissionSettings)
        }
        state.informationMessage?.let { message -> InstructionCard(message) }

        if (state.existingAliases.isNotEmpty()) {
            ExistingAliasesSection(
                aliases = state.existingAliases,
                deletionEnabled = state.stage in setOf(
                    AliasEnrollmentStage.READY_FIRST,
                    AliasEnrollmentStage.UNAVAILABLE,
                ) && !state.isDeletingAlias,
                onRequestDeletion = onRequestAliasDeletion,
            )
        }

        state.pendingAliasDeletion?.let { alias ->
            AlertDialog(
                onDismissRequest = onCancelAliasDeletion,
                title = { Text("确认删除称呼") },
                text = {
                    Text("确定删除“${alias.displayText}”吗？删除后将立即停止用这个发音匹配联系人。")
                },
                confirmButton = {
                    Button(
                        enabled = !state.isDeletingAlias,
                        onClick = onConfirmAliasDeletion,
                    ) {
                        Text(if (state.isDeletingAlias) "正在删除" else "确认删除")
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !state.isDeletingAlias,
                        onClick = onCancelAliasDeletion,
                    ) {
                        Text("暂不删除")
                    }
                },
            )
        }

        if (state.stage !in setOf(
                AliasEnrollmentStage.IDLE,
                AliasEnrollmentStage.CHECKING_CONSENT,
                AliasEnrollmentStage.CONSENT_REQUIRED,
                AliasEnrollmentStage.SAVING_CONSENT,
                AliasEnrollmentStage.UNAVAILABLE,
                AliasEnrollmentStage.COMPLETED,
            )
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "展示称呼输入框" },
                value = state.displayText,
                onValueChange = onDisplayTextChanged,
                enabled = state.stage != AliasEnrollmentStage.SUBMITTING,
                singleLine = true,
                label = { Text("展示称呼") },
                supportingText = {
                    Text("例如“二女儿”。这行文字不代替发音识别。")
                },
            )
        }

        when (state.stage) {
            AliasEnrollmentStage.IDLE -> LoadingSection("正在准备称呼录制")
            AliasEnrollmentStage.CHECKING_CONSENT ->
                LoadingSection("正在读取个人语音模板授权")
            AliasEnrollmentStage.CONSENT_REQUIRED -> AliasConsentSection(onGrantConsent)
            AliasEnrollmentStage.SAVING_CONSENT ->
                LoadingSection("正在保存个人语音模板授权")
            AliasEnrollmentStage.READY_FIRST -> ReadyFirstSection(onRequestRecording)
            AliasEnrollmentStage.RECORDING_FIRST -> RecordingSection(
                label = "正在录第一遍",
                onFinishRecording = onFinishRecording,
            )
            AliasEnrollmentStage.CHECKING_FIRST -> LoadingSection("正在检查第一遍录音")
            AliasEnrollmentStage.FIRST_RECORDED -> FirstRecordedSection(
                durationMs = state.firstDurationMs,
                isPlaying = state.playingRecording == AliasRecordingSlot.FIRST,
                onPlay = { onPlay(AliasRecordingSlot.FIRST) },
                onRetake = { onRetake(AliasRecordingSlot.FIRST) },
                onRecordSecond = onRequestRecording,
            )
            AliasEnrollmentStage.RECORDING_SECOND -> RecordingSection(
                label = "正在录第二遍",
                onFinishRecording = onFinishRecording,
            )
            AliasEnrollmentStage.CHECKING_SECOND ->
                LoadingSection("正在裁剪静音并检查两遍发音是否一致")
            AliasEnrollmentStage.REVIEW -> ReviewSection(
                state = state,
                onPlay = onPlay,
                onRetake = onRetake,
                onConfirmAndSubmit = onConfirmAndSubmit,
            )
            AliasEnrollmentStage.SUBMITTING -> LoadingSection(
                "正在上传两遍录音并检查称呼，失败后不会自动重试",
            )
            AliasEnrollmentStage.COMPLETED -> CompletedSection(
                aliasText = state.completedAliasText.orEmpty(),
                canAddAnotherAlias = state.canAddAnotherAlias,
                onContinueAfterCompletion = onContinueAfterCompletion,
                onBack = onBack,
            )
            AliasEnrollmentStage.UNAVAILABLE -> Text(
                text = "当前不能新增称呼，请返回联系人页面。",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun AliasConsentSection(onGrant: () -> Unit) {
    InstructionCard(
        "联系人称呼的两遍录音只用于识别您对这位亲友的个人叫法，" +
            "不会用于判断是谁在说话，也不会用于通用模型训练。",
    )
    Text(
        "不同意时不会开始录音或上传。以后可以在隐私设置中撤回授权。",
        style = MaterialTheme.typography.bodyLarge,
    )
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        onClick = onGrant,
    ) {
        Text("同意保存个人语音模板", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ExistingAliasesSection(
    aliases: List<AliasSummaryUiState>,
    deletionEnabled: Boolean,
    onRequestDeletion: (String) -> Unit,
) {
    Text("已有称呼", style = MaterialTheme.typography.headlineSmall)
    aliases.forEach { alias ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(alias.displayText, style = MaterialTheme.typography.bodyLarge)
                if (!alias.compatible) {
                    Text(
                        "这个称呼由旧版本录制，当前不能用于联系亲友。请删除后只重新录制这个称呼；安全指令无需重录。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    enabled = deletionEnabled,
                    onClick = { onRequestDeletion(alias.id) },
                ) {
                    Text("删除这个称呼")
                }
            }
        }
    }
}

@Composable
private fun ReadyFirstSection(onRecord: () -> Unit) {
    InstructionCard(
        "请在安静环境中，用平时的说法录两遍。说完立即停止，短称呼无需故意拖长。",
    )
    Button(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        onClick = onRecord,
    ) {
        Text("开始录第一遍", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ColumnScope.RecordingSection(
    label: String,
    onFinishRecording: () -> Unit,
) {
    CircularProgressIndicator(
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .semantics { contentDescription = label },
    )
    Text("$label，最长 5 秒", style = MaterialTheme.typography.headlineSmall)
    Button(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        onClick = onFinishRecording,
    ) {
        Text("说完了，停止录音", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun FirstRecordedSection(
    durationMs: Int?,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onRetake: () -> Unit,
    onRecordSecond: () -> Unit,
) {
    RecordingSummary(
        title = "第一遍",
        durationMs = durationMs,
        isPlaying = isPlaying,
        onPlay = onPlay,
        onRetake = onRetake,
    )
    Button(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        enabled = !isPlaying,
        onClick = onRecordSecond,
    ) {
        Text("开始录第二遍", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ReviewSection(
    state: AliasEnrollmentUiState,
    onPlay: (AliasRecordingSlot) -> Unit,
    onRetake: (AliasRecordingSlot) -> Unit,
    onConfirmAndSubmit: () -> Unit,
) {
    Text("请回放确认两遍都是同一个称呼。", style = MaterialTheme.typography.headlineSmall)
    RecordingSummary(
        title = "第一遍",
        durationMs = state.firstDurationMs,
        isPlaying = state.playingRecording == AliasRecordingSlot.FIRST,
        enabled = state.playingRecording == null,
        onPlay = { onPlay(AliasRecordingSlot.FIRST) },
        onRetake = { onRetake(AliasRecordingSlot.FIRST) },
    )
    RecordingSummary(
        title = "第二遍",
        durationMs = state.secondDurationMs,
        isPlaying = state.playingRecording == AliasRecordingSlot.SECOND,
        enabled = state.playingRecording == null,
        onPlay = { onPlay(AliasRecordingSlot.SECOND) },
        onRetake = { onRetake(AliasRecordingSlot.SECOND) },
    )
    InstructionCard(
        "确认后，系统只用录音识别这个称呼的发音，不用来判断是谁在说话。",
    )
    Button(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        enabled = state.displayText.isNotBlank() && state.playingRecording == null,
        onClick = onConfirmAndSubmit,
    ) {
        Text("确认保存称呼", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun RecordingSummary(
    title: String,
    durationMs: Int?,
    isPlaying: Boolean,
    enabled: Boolean = true,
    onPlay: () -> Unit,
    onRetake: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "$title：${formatDuration(durationMs)}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp),
                    enabled = enabled && !isPlaying,
                    onClick = onPlay,
                ) {
                    Text(if (isPlaying) "正在回放" else "回放")
                }
                OutlinedButton(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp),
                    enabled = enabled && !isPlaying,
                    onClick = onRetake,
                ) {
                    Text("重录$title")
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.LoadingSection(message: String) {
    CircularProgressIndicator(
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .semantics { contentDescription = message },
    )
    Text(message, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun CompletedSection(
    aliasText: String,
    canAddAnotherAlias: Boolean,
    onContinueAfterCompletion: () -> Unit,
    onBack: () -> Unit,
) {
    InstructionCard("已保存称呼“$aliasText”。两段本地原始录音已清理。")
    if (canAddAnotherAlias) {
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp),
            onClick = onContinueAfterCompletion,
        ) {
            Text("继续添加称呼", style = MaterialTheme.typography.bodyLarge)
        }
    }
    Button(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        onClick = onBack,
    ) {
        Text("返回家人页面", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun InstructionCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            modifier = Modifier.padding(16.dp),
            text = message,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun ErrorCard(
    message: String,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("操作未完成：${message.toChineseUiMessage("请稍后重试。")}", style = MaterialTheme.typography.bodyLarge)
            TextButton(
                modifier = Modifier.heightIn(min = 56.dp),
                onClick = onDismiss,
            ) {
                Text("知道了", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun formatDuration(durationMs: Int?): String = durationMs?.let { value ->
    "%.1f 秒".format(value / 1_000.0)
} ?: "未录制"
