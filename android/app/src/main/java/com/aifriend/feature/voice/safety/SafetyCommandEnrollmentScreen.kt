package com.aifriend.feature.voice.safety

import com.aifriend.app.ui.components.toChineseUiMessage

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aifriend.app.ui.components.MicrophonePermissionRecoveryCard
import com.aifriend.app.ui.components.openApplicationDetailsSettings

/**
 * 带麦克风运行时权限处理的安全指令注册入口。
 *
 * @author codex
 * @since 2026-08-13
 */
@Composable
fun SafetyCommandEnrollmentRoute(
    state: SafetyCommandEnrollmentUiState,
    onBack: () -> Unit,
    onGrantConsent: () -> Unit,
    onStartFullReplacement: () -> Unit,
    onSelectCurrentPhrase: (Int) -> Unit,
    onStartRecording: () -> Unit,
    onFinishRecording: () -> Unit,
    onPermissionDenied: () -> Unit,
    onPlay: (SafetyRecordingSlot) -> Unit,
    onRetake: (SafetyRecordingTake) -> Unit,
    onConfirmCurrentCommand: () -> Unit,
    onRedoCommand: (Int) -> Unit,
    onConfirmAndSubmitAll: () -> Unit,
    onDismissError: () -> Unit,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) onStartRecording() else onPermissionDenied()
    }
    SafetyCommandEnrollmentScreen(
        state = state,
        onBack = onBack,
        onGrantConsent = onGrantConsent,
        onStartFullReplacement = onStartFullReplacement,
        onSelectCurrentPhrase = onSelectCurrentPhrase,
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
        onConfirmCurrentCommand = onConfirmCurrentCommand,
        onRedoCommand = onRedoCommand,
        onConfirmAndSubmitAll = onConfirmAndSubmitAll,
        onOpenAppPermissionSettings = { context.openApplicationDetailsSettings() },
        onDismissError = onDismissError,
    )
}

/**
 * 四类安全指令双录页面。页面不持有音频字节。
 *
 * @author codex
 * @since 2026-08-13
 */
@Composable
fun SafetyCommandEnrollmentScreen(
    state: SafetyCommandEnrollmentUiState,
    onBack: () -> Unit,
    onGrantConsent: () -> Unit,
    onStartFullReplacement: () -> Unit,
    onSelectCurrentPhrase: (Int) -> Unit,
    onRequestRecording: () -> Unit,
    onFinishRecording: () -> Unit,
    onPlay: (SafetyRecordingSlot) -> Unit,
    onRetake: (SafetyRecordingTake) -> Unit,
    onConfirmCurrentCommand: () -> Unit,
    onRedoCommand: (Int) -> Unit,
    onConfirmAndSubmitAll: () -> Unit,
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
        Text("录制安全指令", style = MaterialTheme.typography.headlineLarge)
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            onClick = onBack,
        ) {
            Text("取消并返回", style = MaterialTheme.typography.bodyLarge)
        }
        state.errorMessage?.let { message ->
            ErrorCard(message, onDismissError)
        }
        if (state.microphonePermissionRecoveryRequired) {
            MicrophonePermissionRecoveryCard(onOpenAppPermissionSettings)
        }
        if (state.stage !in setOf(
                SafetyCommandEnrollmentStage.IDLE,
                SafetyCommandEnrollmentStage.CHECKING_CONSENT,
                SafetyCommandEnrollmentStage.CONSENT_REQUIRED,
                SafetyCommandEnrollmentStage.SAVING_CONSENT,
                SafetyCommandEnrollmentStage.EXISTING_STATUS_UNAVAILABLE,
                SafetyCommandEnrollmentStage.EXISTING_COMPLETE,
                SafetyCommandEnrollmentStage.REVIEW_ALL,
                SafetyCommandEnrollmentStage.SUBMITTING,
                SafetyCommandEnrollmentStage.COMPLETED,
            )
        ) {
            val progress = state.commands[state.currentCommandIndex]
            val command = progress.definition
            InstructionCard(
                "第 ${state.currentCommandIndex + 1} 类，共 4 类\n请用平时的方言说：“${progress.selectedSpokenText}”\n${command.explanation}",
            )
        }
        when (state.stage) {
            SafetyCommandEnrollmentStage.IDLE -> LoadingSection("正在准备安全指令录制")
            SafetyCommandEnrollmentStage.CHECKING_CONSENT ->
                LoadingSection("正在读取语音模板授权")
            SafetyCommandEnrollmentStage.CONSENT_REQUIRED -> ConsentSection(onGrantConsent)
            SafetyCommandEnrollmentStage.SAVING_CONSENT ->
                LoadingSection("正在保存语音模板授权")
            SafetyCommandEnrollmentStage.EXISTING_STATUS_UNAVAILABLE ->
                ExistingStatusUnavailableSection(onBack)
            SafetyCommandEnrollmentStage.EXISTING_COMPLETE -> ExistingCompleteSection(
                state = state,
                onReplaceAll = onStartFullReplacement,
                onBack = onBack,
            )
            SafetyCommandEnrollmentStage.READY_FIRST -> ReadyFirstSection(
                progress = state.commands[state.currentCommandIndex],
                onSelectPhrase = onSelectCurrentPhrase,
                onRecord = onRequestRecording,
            )
            SafetyCommandEnrollmentStage.RECORDING_FIRST -> RecordingSection(
                label = "正在录第一遍",
                onFinishRecording = onFinishRecording,
            )
            SafetyCommandEnrollmentStage.CHECKING_FIRST ->
                LoadingSection("正在检查第一遍录音")
            SafetyCommandEnrollmentStage.FIRST_RECORDED -> FirstRecordedSection(
                state = state,
                onPlay = onPlay,
                onRetake = onRetake,
                onRecordSecond = onRequestRecording,
            )
            SafetyCommandEnrollmentStage.RECORDING_SECOND -> RecordingSection(
                label = "正在录第二遍",
                onFinishRecording = onFinishRecording,
            )
            SafetyCommandEnrollmentStage.CHECKING_SECOND ->
                LoadingSection("正在裁剪静音并检查当前指令两遍发音")
            SafetyCommandEnrollmentStage.REVIEW_COMMAND -> CommandReviewSection(
                state = state,
                onPlay = onPlay,
                onRetake = onRetake,
                onConfirm = onConfirmCurrentCommand,
            )
            SafetyCommandEnrollmentStage.REVIEW_ALL -> AllReviewSection(
                state = state,
                onRedoCommand = onRedoCommand,
                onConfirm = onConfirmAndSubmitAll,
            )
            SafetyCommandEnrollmentStage.SUBMITTING -> LoadingSection(
                "正在顺序上传八段录音并保存；结果不明时不会自动重试",
            )
            SafetyCommandEnrollmentStage.COMPLETED -> CompletedSection(onBack)
        }
    }
}

@Composable
private fun ExistingCompleteSection(
    state: SafetyCommandEnrollmentUiState,
    onReplaceAll: () -> Unit,
    onBack: () -> Unit,
) {
    InstructionCard(
        if (state.existingLocalTemplatesReady) {
            "服务端已有完整四类安全指令，本机模板也可以继续使用。重新录制必须替换全部四类；" +
                "只有最后确认保存成功后才会替换，直接返回不会影响现有指令。"
        } else {
            "服务端已有完整四类安全指令，但本机识别材料当前不可用。已有模板不会被隐藏；" +
                "如需恢复本机识别，请明确重新录制全部四类。直接返回不会删除服务端模板。"
        },
    )
    SafetyCommandDefinition.entries.forEach { definition ->
        val saved = definition.contractType in state.existingServerTemplateTypes
        Text(
            "${definition.phraseOptions.first()}：${if (saved) "已保存" else "本机可用"}",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        onClick = onReplaceAll,
    ) {
        Text("重新录制全部四类", style = MaterialTheme.typography.bodyLarge)
    }
    OutlinedButton(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        onClick = onBack,
    ) {
        Text(
            if (state.existingLocalTemplatesReady) "保留现有指令并返回" else "暂不重录，返回",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun ExistingStatusUnavailableSection(onBack: () -> Unit) {
    InstructionCard(
        "当前无法核对服务端已有模板。为避免误覆盖，本次不会自动进入录制；请返回后检查网络再重试。",
    )
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        onClick = onBack,
    ) {
        Text("返回", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ConsentSection(onGrant: () -> Unit) {
    InstructionCard(
        "安全指令模板只用于识别“发送消息、拨打电话、取消这次、重新说一遍”的发音内容，" +
            "不会用于判断是谁在说话，也不会用于通用模型训练。",
    )
    Text(
        "不同意时不会录音或上传。以后可以在隐私设置中撤回授权。",
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
private fun ReadyFirstSection(
    progress: SafetyCommandProgress,
    onSelectPhrase: (Int) -> Unit,
    onRecord: () -> Unit,
) {
    Text("先选择一种平时最顺口的说法，只需录所选这一条。", style = MaterialTheme.typography.bodyLarge)
    progress.definition.phraseOptions.forEachIndexed { index, phrase ->
        if (index == progress.selectedPhraseIndex) {
            Button(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                onClick = { onSelectPhrase(index) },
            ) {
                Text("已选择：$phrase", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                onClick = { onSelectPhrase(index) },
            ) {
                Text("改用：$phrase", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    Text(
        "请在安静环境中完整说出固定短句，说完立即停止；“取消”等短词无需拖长。",
        style = MaterialTheme.typography.bodyLarge,
    )
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
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
        modifier = Modifier.align(Alignment.CenterHorizontally)
            .semantics { contentDescription = label },
    )
    Text("$label，最长 5 秒", style = MaterialTheme.typography.headlineSmall)
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        onClick = onFinishRecording,
    ) {
        Text("说完了，停止录音", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun FirstRecordedSection(
    state: SafetyCommandEnrollmentUiState,
    onPlay: (SafetyRecordingSlot) -> Unit,
    onRetake: (SafetyRecordingTake) -> Unit,
    onRecordSecond: () -> Unit,
) {
    val index = state.currentCommandIndex
    val progress = state.commands[index]
    RecordingSummary(
        title = "第一遍",
        durationMs = progress.firstDurationMs,
        isPlaying = state.playingRecording == SafetyRecordingSlot(index, SafetyRecordingTake.FIRST),
        onPlay = { onPlay(SafetyRecordingSlot(index, SafetyRecordingTake.FIRST)) },
        onRetake = { onRetake(SafetyRecordingTake.FIRST) },
    )
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        enabled = state.playingRecording == null,
        onClick = onRecordSecond,
    ) {
        Text("开始录第二遍", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CommandReviewSection(
    state: SafetyCommandEnrollmentUiState,
    onPlay: (SafetyRecordingSlot) -> Unit,
    onRetake: (SafetyRecordingTake) -> Unit,
    onConfirm: () -> Unit,
) {
    val index = state.currentCommandIndex
    val progress = state.commands[index]
    Text("请试听，确认两遍都是“${progress.selectedSpokenText}”。", style = MaterialTheme.typography.headlineSmall)
    SafetyRecordingTake.entries.forEach { take ->
        val slot = SafetyRecordingSlot(index, take)
        RecordingSummary(
            title = if (take == SafetyRecordingTake.FIRST) "第一遍" else "第二遍",
            durationMs = if (take == SafetyRecordingTake.FIRST) {
                progress.firstDurationMs
            } else {
                progress.secondDurationMs
            },
            isPlaying = state.playingRecording == slot,
            enabled = state.playingRecording == null,
            onPlay = { onPlay(slot) },
            onRetake = { onRetake(take) },
        )
    }
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        enabled = state.playingRecording == null,
        onClick = onConfirm,
    ) {
        Text(
            if (index == state.commands.lastIndex) "确认这一类，查看全部" else "确认这一类，继续下一类",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun AllReviewSection(
    state: SafetyCommandEnrollmentUiState,
    onRedoCommand: (Int) -> Unit,
    onConfirm: () -> Unit,
) {
    InstructionCard("四类指令共八段录音已准备。点击最终确认后才会上传和保存。")
    state.commands.forEachIndexed { index, progress ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(progress.selectedSpokenText, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "第一遍 ${formatDuration(progress.firstDurationMs)}；第二遍 ${formatDuration(progress.secondDurationMs)}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    onClick = { onRedoCommand(index) },
                ) {
                    Text("重新录这一类")
                }
            }
        }
    }
    Text(
        "最终确认仅注册发音模板，不会发送消息或发起通话。",
        style = MaterialTheme.typography.bodyLarge,
    )
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        enabled = state.commands.all { it.reviewed },
        onClick = onConfirm,
    ) {
        Text("确认注册四类安全指令", style = MaterialTheme.typography.bodyLarge)
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
            Text("$title：${formatDuration(durationMs)}", style = MaterialTheme.typography.bodyLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    enabled = enabled && !isPlaying,
                    onClick = onPlay,
                ) {
                    Text(if (isPlaying) "正在回放" else "回放")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
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
        modifier = Modifier.align(Alignment.CenterHorizontally)
            .semantics { contentDescription = message },
    )
    Text(message, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun CompletedSection(onBack: () -> Unit) {
    InstructionCard(
        "服务端已确认四类安全指令完整保存，八段本地原始录音已清理。" +
            "正式声学包未启用时本页不会进入成功状态。",
    )
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        onClick = onBack,
    ) {
        Text("完成并返回", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun InstructionCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(modifier = Modifier.padding(16.dp), text = message, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("操作未完成：${message.toChineseUiMessage("请稍后重试。")}", style = MaterialTheme.typography.bodyLarge)
            TextButton(modifier = Modifier.heightIn(min = 56.dp), onClick = onDismiss) {
                Text("知道了", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun formatDuration(durationMs: Int?): String = durationMs?.let { value ->
    "%.1f 秒".format(value / 1_000.0)
} ?: "未录制"
