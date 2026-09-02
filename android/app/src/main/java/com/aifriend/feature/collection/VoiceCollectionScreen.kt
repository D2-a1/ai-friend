package com.aifriend.feature.collection

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aifriend.app.ui.components.MicrophonePermissionRecoveryCard
import com.aifriend.app.ui.components.openApplicationDetailsSettings
import com.aifriend.contract.model.ConsentDecision
import com.aifriend.contract.model.VoiceCollectionReviewStatus

/**
 * 带麦克风权限处理的封闭测试语音采集入口。
 *
 * @author codex
 * @since 2026-08-20
 */
@Composable
fun VoiceCollectionRoute(
    viewModel: VoiceCollectionViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startRecording() else viewModel.onMicrophonePermissionDenied()
    }
    VoiceCollectionScreen(
        state = state,
        onBack = onBack,
        onGrantConsent = viewModel::grantConsent,
        onSelectEnvironment = viewModel::selectEnvironment,
        onRecord = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.startRecording()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onStop = viewModel::finishRecording,
        onPlay = viewModel::play,
        onRetake = viewModel::retake,
        onReviewTranscriptChange = viewModel::updateReviewTranscript,
        onSubmit = viewModel::submit,
        onRequestDelete = viewModel::requestDelete,
        onCancelDelete = viewModel::cancelDelete,
        onConfirmDelete = viewModel::confirmDelete,
        onRequestTrainingAuthorization = viewModel::requestTrainingAuthorization,
        onCancelTrainingAuthorization = viewModel::cancelTrainingAuthorization,
        onConfirmTrainingAuthorization = viewModel::confirmTrainingAuthorization,
        onRequestRevokeTrainingConsent = viewModel::requestRevokeTrainingConsent,
        onCancelRevokeTrainingConsent = viewModel::cancelRevokeTrainingConsent,
        onConfirmRevokeTrainingConsent = viewModel::confirmRevokeTrainingConsent,
        onRequestRevoke = viewModel::requestRevokeConsent,
        onCancelRevoke = viewModel::cancelRevokeConsent,
        onConfirmRevoke = viewModel::confirmRevokeConsent,
        onOpenAppPermissionSettings = { context.openApplicationDetailsSettings() },
        onDismissError = viewModel::dismissError,
    )
}

/** 封闭测试采集页面。页面状态不持有原始音频。 */
@Composable
fun VoiceCollectionScreen(
    state: VoiceCollectionUiState,
    onBack: () -> Unit,
    onGrantConsent: () -> Unit,
    onSelectEnvironment: (com.aifriend.contract.model.VoiceCollectionEnvironment) -> Unit,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onPlay: () -> Unit,
    onRetake: () -> Unit,
    onReviewTranscriptChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onRequestDelete: (String) -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onRequestRevoke: () -> Unit,
    onRequestTrainingAuthorization: (String) -> Unit,
    onCancelTrainingAuthorization: () -> Unit,
    onConfirmTrainingAuthorization: () -> Unit,
    onRequestRevokeTrainingConsent: () -> Unit,
    onCancelRevokeTrainingConsent: () -> Unit,
    onConfirmRevokeTrainingConsent: () -> Unit,
    onCancelRevoke: () -> Unit,
    onConfirmRevoke: () -> Unit,
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
        Text("封闭测试语音采集版", style = MaterialTheme.typography.headlineLarge)
        NoticeCard(
            "这里只收集您主动录制的武冈话测试样本。原始音频加密私有保存，最长 30 天；" +
                "不会自动用于训练。只有您另行同意训练并明确选择的样本才能用于训练；" +
                "本版本不会启用正式识别，也不会打开微信、发消息或打电话。",
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            onClick = onBack,
        ) { Text("清理当前录音并返回") }

        state.errorMessage?.let { message ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(message, style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = onDismissError) { Text("知道了") }
                }
            }
        }
        if (state.microphonePermissionRecoveryRequired) {
            MicrophonePermissionRecoveryCard(onOpenAppPermissionSettings)
        }

        when (state.stage) {
            VoiceCollectionStage.IDLE,
            VoiceCollectionStage.LOADING,
            -> Loading("正在读取独立采集授权")
            VoiceCollectionStage.CONSENT_REQUIRED -> ConsentSection(onGrantConsent)
            VoiceCollectionStage.SAVING_CONSENT -> Loading("正在保存独立采集授权")
            VoiceCollectionStage.READY -> ReadySection(
                state,
                onSelectEnvironment,
                onRecord,
                onRequestDelete,
                onRequestTrainingAuthorization,
                onRequestRevokeTrainingConsent,
                onRequestRevoke,
            )
            VoiceCollectionStage.RECORDING -> {
                Text("正在录音，最长 5 秒", style = MaterialTheme.typography.headlineSmall)
                Button(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    onClick = onStop,
                ) { Text("说完了，停止录音") }
            }
            VoiceCollectionStage.CHECKING -> Loading("正在本机检查录音质量")
            VoiceCollectionStage.REVIEW -> ReviewSection(
                state,
                onPlay,
                onRetake,
                onReviewTranscriptChange,
                onSubmit,
            )
            VoiceCollectionStage.SUBMITTING -> Loading("正在加密上传；失败后不会自动补传")
            VoiceCollectionStage.DELETING -> Loading("正在受理样本删除")
            VoiceCollectionStage.UPDATING_TRAINING_AUTHORIZATION ->
                Loading("正在保存这条样本的训练授权")
            VoiceCollectionStage.REVOKING_TRAINING_CONSENT ->
                Loading("正在撤回全部训练授权")
            VoiceCollectionStage.REVOKING -> Loading("正在撤回授权并停止全部样本使用")
        }
    }

    if (state.pendingDeleteSampleId != null) {
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text("确认删除这条样本？") },
            text = { Text("确认后立即停止使用并进入物理删除队列，不能撤销。") },
            confirmButton = { TextButton(onClick = onConfirmDelete) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = onCancelDelete) { Text("保留") } },
        )
    }
    if (state.revokeConfirmationPending) {
        AlertDialog(
            onDismissRequest = onCancelRevoke,
            title = { Text("撤回测试语音采集授权？") },
            text = {
                Text("确认后停止全部样本使用并进入删除队列。以后如要继续采集，需要重新同意。")
            },
            confirmButton = { TextButton(onClick = onConfirmRevoke) { Text("确认撤回") } },
            dismissButton = { TextButton(onClick = onCancelRevoke) { Text("暂不撤回") } },
        )
    }
    if (state.pendingTrainingSampleId != null && state.pendingTrainingDecision != null) {
        val granting = state.pendingTrainingDecision == ConsentDecision.GRANTED
        AlertDialog(
            onDismissRequest = onCancelTrainingAuthorization,
            title = {
                Text(if (granting) "允许这条样本用于训练？" else "撤回这条训练授权？")
            },
            text = {
                Text(
                    if (granting) {
                        "如果您尚未同意模型训练，本次确认会同时保存独立的训练总授权，" +
                            "并只把这一条测试数据授权给未来训练。" +
                            "这不代表已经训练，也不会启用正式识别。"
                    } else {
                        "确认后立即停止把这条样本用于未来训练选择。本版本尚未执行真实训练。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmTrainingAuthorization) {
                    Text(if (granting) "同意用于训练" else "确认撤回")
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelTrainingAuthorization) { Text("暂不修改") }
            },
        )
    }
    if (state.revokeTrainingConsentConfirmationPending) {
        AlertDialog(
            onDismissRequest = onCancelRevokeTrainingConsent,
            title = { Text("撤回全部训练授权？") },
            text = {
                Text("确认后，全部有效测试样本立即停止用于未来训练选择；采集样本仍按原留存和删除规则管理。")
            },
            confirmButton = {
                TextButton(onClick = onConfirmRevokeTrainingConsent) { Text("确认全部撤回") }
            },
            dismissButton = {
                TextButton(onClick = onCancelRevokeTrainingConsent) { Text("暂不撤回") }
            },
        )
    }
}

@Composable
private fun ConsentSection(onGrantConsent: () -> Unit) {
    NoticeCard(
        "您可以随时删除单条样本或撤回全部采集授权。采集授权只允许保存测试样本；" +
            "是否用于训练由您另行决定，未明确同意的样本始终只用于测试。",
    )
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        onClick = onGrantConsent,
    ) { Text("我已了解，同意参与封闭测试采集") }
}

@Composable
private fun ReadySection(
    state: VoiceCollectionUiState,
    onSelectEnvironment: (com.aifriend.contract.model.VoiceCollectionEnvironment) -> Unit,
    onRecord: () -> Unit,
    onRequestDelete: (String) -> Unit,
    onRequestTrainingAuthorization: (String) -> Unit,
    onRequestRevokeTrainingConsent: () -> Unit,
    onRequestRevoke: () -> Unit,
) {
    val prompt = state.currentPrompt
    Text(
        "第 ${state.promptIndex + 1}/${VOICE_COLLECTION_PROMPTS.size} 条",
        style = MaterialTheme.typography.titleLarge,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("请说：${prompt.spokenText}", style = MaterialTheme.typography.headlineSmall)
            Text(prompt.explanation, style = MaterialTheme.typography.bodyLarge)
        }
    }
    Text("选择录音环境", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EnvironmentButton("安静", com.aifriend.contract.model.VoiceCollectionEnvironment.QUIET, state, onSelectEnvironment)
        EnvironmentButton("家中有杂音", com.aifriend.contract.model.VoiceCollectionEnvironment.HOME_NOISE, state, onSelectEnvironment)
        EnvironmentButton("户外", com.aifriend.contract.model.VoiceCollectionEnvironment.OUTDOOR, state, onSelectEnvironment)
    }
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        onClick = onRecord,
    ) { Text("开始录这条测试语音") }

    Text("已提交样本：${state.samples.size} 条", style = MaterialTheme.typography.titleLarge)
    state.samples.forEach { sample ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(sampleLabel(sample.promptCode), style = MaterialTheme.typography.bodyLarge)
                Text("最迟删除：${sample.retentionUntil.toLocalDate()}")
                Text(
                    if (sample.trainingEligible) "未来训练：已授权（尚未训练）" else "未来训练：未授权，仅测试",
                )
                Text(
                    if (sample.reviewStatus == VoiceCollectionReviewStatus.CONFIRMED) {
                        "人工复核：已完成"
                    } else {
                        "人工复核：旧样本未复核，需要重新录制"
                    },
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    enabled = sample.trainingEligible ||
                        sample.reviewStatus == VoiceCollectionReviewStatus.CONFIRMED,
                    onClick = { onRequestTrainingAuthorization(sample.sampleId) },
                ) {
                    Text(
                        when {
                            sample.trainingEligible -> "撤回这条训练授权"
                            sample.reviewStatus == VoiceCollectionReviewStatus.CONFIRMED ->
                                "同意这条用于训练"
                            else -> "未复核，需重新录制"
                        },
                    )
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    onClick = { onRequestDelete(sample.sampleId) },
                ) { Text("删除这条样本") }
            }
        }
    }
    OutlinedButton(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        onClick = onRequestRevoke,
    ) { Text("撤回全部测试语音采集授权") }
    if (state.trainingConsentGranted) {
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            onClick = onRequestRevokeTrainingConsent,
        ) { Text("撤回全部训练授权") }
    }
}

@Composable
private fun RowScope.EnvironmentButton(
    label: String,
    value: com.aifriend.contract.model.VoiceCollectionEnvironment,
    state: VoiceCollectionUiState,
    onSelect: (com.aifriend.contract.model.VoiceCollectionEnvironment) -> Unit,
) {
    OutlinedButton(
        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
        enabled = state.environment != value,
        onClick = { onSelect(value) },
    ) { Text(if (state.environment == value) "已选$label" else label) }
}

@Composable
private fun ReviewSection(
    state: VoiceCollectionUiState,
    onPlay: () -> Unit,
    onRetake: () -> Unit,
    onReviewTranscriptChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Text("录音约 ${"%.1f".format((state.recordedDurationMs ?: 0) / 1_000.0)} 秒")
    NoticeCard("请先试听，再核对实际说出的文字。提交后文字会加密保存，不会在样本列表返回。")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            enabled = !state.isPlaying,
            onClick = onPlay,
        ) { Text(if (state.isPlaying) "正在试听" else "试听") }
        OutlinedButton(
            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            enabled = !state.isPlaying,
            onClick = onRetake,
        ) { Text("重录") }
    }
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        enabled = state.reviewPlaybackCompleted,
        value = state.reviewTranscript,
        onValueChange = onReviewTranscriptChange,
        label = { Text("核对录音文字") },
        supportingText = {
            Text(
                if (state.reviewPlaybackCompleted) {
                    "固定句已预填；自由称呼请填写录音中实际说出的称呼"
                } else {
                    "请先完整试听，再核对文字"
                },
            )
        },
        minLines = 2,
    )
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        enabled = !state.isPlaying && state.reviewPlaybackCompleted &&
            state.reviewTranscript.isNotBlank(),
        onClick = onSubmit,
    ) { Text("确认文字并提交样本") }
}

@Composable
private fun Loading(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun NoticeCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(message, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

internal fun sampleLabel(promptCode: String): String = VOICE_COLLECTION_PROMPTS
    .firstOrNull { it.code == promptCode }
    ?.let { "${it.category.userLabel()}：${it.spokenText}" }
    ?: "测试样本"
