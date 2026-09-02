package com.aifriend.feature.guardian.wake

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aifriend.app.ui.components.MicrophonePermissionRecoveryCard
import com.aifriend.app.ui.components.openApplicationDetailsSettings

/** 带麦克风权限处理的个人“小友”双录入口。 */
@Composable
fun WakeWordEnrollmentRoute(
    state: WakeWordEnrollmentUiState,
    onBack: () -> Unit,
    onStartRecording: () -> Unit,
    onFinishRecording: () -> Unit,
    onPermissionDenied: () -> Unit,
    onPlay: (WakeWordRecordingSlot) -> Unit,
    onRetake: (WakeWordRecordingSlot) -> Unit,
    onConfirmSave: () -> Unit,
    onRecordAgain: () -> Unit,
    onDismissError: () -> Unit,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onStartRecording() else onPermissionDenied() }
    WakeWordEnrollmentScreen(
        state = state,
        onBack = onBack,
        onRequestRecording = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) onStartRecording() else launcher.launch(Manifest.permission.RECORD_AUDIO)
        },
        onFinishRecording = onFinishRecording,
        onPlay = onPlay,
        onRetake = onRetake,
        onConfirmSave = onConfirmSave,
        onRecordAgain = onRecordAgain,
        onOpenPermissionSettings = { context.openApplicationDetailsSettings() },
        onDismissError = onDismissError,
    )
}

/** 只展示录音时长和流程状态，不接收原始音频。 */
@Composable
fun WakeWordEnrollmentScreen(
    state: WakeWordEnrollmentUiState,
    onBack: () -> Unit,
    onRequestRecording: () -> Unit,
    onFinishRecording: () -> Unit,
    onPlay: (WakeWordRecordingSlot) -> Unit,
    onRetake: (WakeWordRecordingSlot) -> Unit,
    onConfirmSave: () -> Unit,
    onRecordAgain: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
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
        Text("录制小友唤醒词", style = MaterialTheme.typography.headlineLarge)
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            onClick = onBack,
        ) { Text("取消并返回", style = MaterialTheme.typography.bodyLarge) }
        InfoCard(
            "每一遍只说一次“小友”，用平时的方言和语速。实际唤醒时仍要说“小友、小友”，两次之间稍停一下。",
        )
        InfoCard("模板只加密保存在本机，用于识别“小友”的发音内容，不用于判断是谁在说话。")
        if (state.hasExistingTemplate && state.stage != WakeWordEnrollmentStage.COMPLETED) {
            InfoCard("本机已有可用模板；重新保存后会安全替换旧模板。")
        }
        state.errorMessage?.let { ErrorCard(it, onDismissError) }
        if (state.microphonePermissionRecoveryRequired) {
            MicrophonePermissionRecoveryCard(onOpenPermissionSettings)
        }
        when (state.stage) {
            WakeWordEnrollmentStage.IDLE -> Loading("正在准备录制")
            WakeWordEnrollmentStage.READY_FIRST -> PrimaryButton(
                "开始录第一遍",
                onRequestRecording,
            )
            WakeWordEnrollmentStage.RECORDING_FIRST -> Recording(
                "正在录第一遍，请说“小友”",
                onFinishRecording,
            )
            WakeWordEnrollmentStage.CHECKING_FIRST -> Loading("正在检查第一遍录音")
            WakeWordEnrollmentStage.FIRST_RECORDED -> {
                RecordingSummary(
                    "第一遍",
                    state.firstDurationMs,
                    state.playingRecording == WakeWordRecordingSlot.FIRST,
                    { onPlay(WakeWordRecordingSlot.FIRST) },
                    { onRetake(WakeWordRecordingSlot.FIRST) },
                )
                PrimaryButton("开始录第二遍", onRequestRecording)
            }
            WakeWordEnrollmentStage.RECORDING_SECOND -> Recording(
                "正在录第二遍，请再说一次“小友”",
                onFinishRecording,
            )
            WakeWordEnrollmentStage.CHECKING_SECOND -> Loading("正在检查两遍发音是否一致")
            WakeWordEnrollmentStage.REVIEW -> {
                Text("请回放确认两遍都只说了一次“小友”。", style = MaterialTheme.typography.headlineSmall)
                RecordingSummary(
                    "第一遍",
                    state.firstDurationMs,
                    state.playingRecording == WakeWordRecordingSlot.FIRST,
                    { onPlay(WakeWordRecordingSlot.FIRST) },
                    { onRetake(WakeWordRecordingSlot.FIRST) },
                )
                RecordingSummary(
                    "第二遍",
                    state.secondDurationMs,
                    state.playingRecording == WakeWordRecordingSlot.SECOND,
                    { onPlay(WakeWordRecordingSlot.SECOND) },
                    { onRetake(WakeWordRecordingSlot.SECOND) },
                )
                PrimaryButton("确认保存小友唤醒词", onConfirmSave)
            }
            WakeWordEnrollmentStage.SAVING -> Loading("正在加密保存本机模板")
            WakeWordEnrollmentStage.COMPLETED -> {
                InfoCard("“小友”已保存，两段原始录音已清理。现在可以返回首页开启小友守护。")
                PrimaryButton("重新录制", onRecordAgain)
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    onClick = onBack,
                ) { Text("返回我的", style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        onClick = onClick,
    ) { Text(text, style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun Recording(text: String, onFinish: () -> Unit) {
    CircularProgressIndicator(modifier = Modifier.fillMaxWidth())
    Text("$text，最长 5 秒", style = MaterialTheme.typography.headlineSmall)
    PrimaryButton("说完了，停止录音", onFinish)
}

@Composable
private fun Loading(text: String) {
    CircularProgressIndicator(modifier = Modifier.fillMaxWidth())
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun RecordingSummary(
    title: String,
    durationMs: Int?,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onRetake: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("$title：${durationMs?.let { "%.1f 秒".format(it / 1_000.0) } ?: "未录制"}")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    enabled = !isPlaying,
                    onClick = onPlay,
                ) { Text(if (isPlaying) "正在回放" else "回放") }
                OutlinedButton(
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    enabled = !isPlaying,
                    onClick = onRetake,
                ) { Text("重录$title") }
            }
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) { Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun ErrorCard(text: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}
