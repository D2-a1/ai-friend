package com.aifriend.feature.task.decision

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aifriend.app.ui.components.MicrophonePermissionRecoveryCard
import com.aifriend.app.ui.components.openApplicationDetailsSettings

@Composable
fun TaskDecisionEnrollmentRoute(
    state: TaskDecisionEnrollmentUiState,
    onBack: () -> Unit,
    onStartRecording: () -> Unit,
    onFinishRecording: () -> Unit,
    onPermissionDenied: () -> Unit,
    onConfirmSave: () -> Unit,
    onRestart: () -> Unit,
    onDismissError: () -> Unit,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onStartRecording() else onPermissionDenied() }
    TaskDecisionEnrollmentScreen(
        state = state,
        onBack = onBack,
        onRequestRecording = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) onStartRecording() else launcher.launch(Manifest.permission.RECORD_AUDIO)
        },
        onFinishRecording = onFinishRecording,
        onConfirmSave = onConfirmSave,
        onRestart = onRestart,
        onOpenPermissionSettings = { context.openApplicationDetailsSettings() },
        onDismissError = onDismissError,
    )
}

@Composable
fun TaskDecisionEnrollmentScreen(
    state: TaskDecisionEnrollmentUiState,
    onBack: () -> Unit,
    onRequestRecording: () -> Unit,
    onFinishRecording: () -> Unit,
    onConfirmSave: () -> Unit,
    onRestart: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onDismissError: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("录制任务确认词", style = MaterialTheme.typography.headlineLarge)
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            onClick = onBack,
        ) { Text("取消并返回", style = MaterialTheme.typography.bodyLarge) }
        Info(
            "系统播报完整任务后，会自动听您说“确认”或“否认”。两类各录两遍，" +
                "用于识别您的方言发音；模板只加密保存在本机。",
        )
        if (state.hasExistingTemplates &&
            state.stage != TaskDecisionEnrollmentStage.COMPLETED
        ) {
            Info("本机已有可用确认词模板；只有新模板完整保存后才会替换。")
        }
        state.errorMessage?.let { message ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(message, style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = onDismissError) { Text("知道了") }
                }
            }
        }
        if (state.microphonePermissionRecoveryRequired) {
            MicrophonePermissionRecoveryCard(onOpenPermissionSettings)
        }
        state.completedTypes.forEach { type ->
            Text(type.spokenText + "：两遍已通过", style = MaterialTheme.typography.bodyLarge)
        }
        when (state.stage) {
            TaskDecisionEnrollmentStage.IDLE -> Loading("正在检查已有模板")
            TaskDecisionEnrollmentStage.READY -> {
                Info(
                    "请用平时的方言清楚说一次“" + state.currentType.spokenText + "”。" +
                        "当前录第 " + state.currentTake + " 遍，共 2 遍。",
                )
                Primary("开始录第 " + state.currentTake + " 遍", onRequestRecording)
            }
            TaskDecisionEnrollmentStage.RECORDING -> {
                Loading(
                    "正在录“" + state.currentType.spokenText + "”第 " +
                        state.currentTake + " 遍，最长 5 秒",
                )
                Primary("说完了，停止录音", onFinishRecording)
            }
            TaskDecisionEnrollmentStage.CHECKING -> Loading("正在检查录音质量和两词区分度")
            TaskDecisionEnrollmentStage.READY_TO_SAVE -> {
                Info("“确认”和“否认”均已双录完成。保存成功前不会替换现有模板。")
                Primary("确认保存两类模板", onConfirmSave)
            }
            TaskDecisionEnrollmentStage.SAVING -> Loading("正在加密保存本机模板")
            TaskDecisionEnrollmentStage.COMPLETED -> {
                Info("确认词已保存，四段原始录音已清理。后续任务会优先匹配您的个人发音。")
                Primary("重新录制", onRestart)
            }
        }
    }
}

@Composable
private fun Primary(text: String, onClick: () -> Unit) {
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        onClick = onClick,
    ) { Text(text, style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun Loading(text: String) {
    CircularProgressIndicator(modifier = Modifier.fillMaxWidth())
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Info(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) { Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge) }
}