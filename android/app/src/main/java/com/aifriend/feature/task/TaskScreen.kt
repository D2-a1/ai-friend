package com.aifriend.feature.task

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aifriend.app.ui.components.MicrophonePermissionRecoveryCard
import com.aifriend.app.ui.components.openApplicationDetailsSettings
import com.aifriend.contract.model.AllowedAction
import com.aifriend.contract.model.ConfirmationAction
import com.aifriend.contract.model.TaskState
import com.aifriend.core.design.AccessibleStatusIndicator
import kotlinx.coroutines.launch

/** 任务录音、候选选择和动作型确认页面。 */
@Composable
fun TaskRoute(
    viewModel: TaskViewModel,
    onBack: () -> Unit,
    onResumeGuardian: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var pendingMicrophoneRequest by remember { mutableStateOf<TaskMicrophoneRequest?>(null) }
    val executeMicrophoneRequest: (TaskMicrophoneRequest) -> Unit = { request ->
        when (request) {
            TaskMicrophoneRequest.StartTask -> viewModel.startTaskRecording()
            TaskMicrophoneRequest.RepeatTask -> viewModel.repeatTaskRecording()
            TaskMicrophoneRequest.ContinueMessage -> viewModel.continueMessage()
            is TaskMicrophoneRequest.Confirm -> viewModel.startConfirmation(request.action)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val request = pendingMicrophoneRequest
        pendingMicrophoneRequest = null
        if (granted && request != null) {
            executeMicrophoneRequest(request)
        } else if (request != null) {
            viewModel.onMicrophonePermissionDenied()
        }
    }
    val requestMicrophone: (TaskMicrophoneRequest) -> Unit = { request ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            executeMicrophoneRequest(request)
        } else {
            pendingMicrophoneRequest = request
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    TaskScreen(
        state = state,
        onStartTask = { requestMicrophone(TaskMicrophoneRequest.StartTask) },
        onGrantTaskAudioConsent = viewModel::grantTaskAudioConsent,
        onStopTask = viewModel::finishTaskRecording,
        onSelectCandidate = viewModel::selectCandidate,
        onStartConfirmation = { action ->
            requestMicrophone(TaskMicrophoneRequest.Confirm(action))
        },
        onStopConfirmation = viewModel::finishConfirmation,
        onCancelTask = viewModel::cancelImmediately,
        onRepeatTask = { requestMicrophone(TaskMicrophoneRequest.RepeatTask) },
        onRetryRehearsal = viewModel::retryRehearsal,
        onReplaySummary = viewModel::replaySummary,
        onRetry = viewModel::open,
        onContinueMessage = { requestMicrophone(TaskMicrophoneRequest.ContinueMessage) },
        onOpenAppPermissionSettings = { context.openApplicationDetailsSettings() },
        onResumeGuardian = {
            scope.launch {
                if (viewModel.leaveForGuardianResume()) onResumeGuardian()
            }
        },
        onBack = {
            viewModel.leave()
            onBack()
        },
    )
}

/** 用户明确触发且需要麦克风权限的任务动作。 */
private sealed interface TaskMicrophoneRequest {
    data object StartTask : TaskMicrophoneRequest
    data object RepeatTask : TaskMicrophoneRequest
    data object ContinueMessage : TaskMicrophoneRequest
    data class Confirm(val action: ConfirmationAction) : TaskMicrophoneRequest
}

/** 老年人大字号任务状态页；不展示技术日志或微信敏感定位。 */
@Composable
fun TaskScreen(
    state: TaskUiState,
    onStartTask: () -> Unit,
    onGrantTaskAudioConsent: () -> Unit = {},
    onStopTask: () -> Unit,
    onSelectCandidate: (String) -> Unit,
    onStartConfirmation: (ConfirmationAction) -> Unit,
    onStopConfirmation: () -> Unit,
    onCancelTask: () -> Unit,
    onRepeatTask: () -> Unit,
    onRetryRehearsal: () -> Unit,
    onReplaySummary: () -> Unit,
    onRetry: () -> Unit,
    onContinueMessage: () -> Unit,
    onOpenAppPermissionSettings: () -> Unit = {},
    onResumeGuardian: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("联系亲友", style = MaterialTheme.typography.headlineLarge)
        AccessibleStatusIndicator(
            presentation = state.feedbackPresentation(),
            message = state.statusMessage,
            contextLabel = "任务状态",
        )
        state.errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        }
        if (state.microphonePermissionRecoveryRequired) {
            MicrophonePermissionRecoveryCard(onOpenAppPermissionSettings)
        }
        state.continuation?.let { continuation ->
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .semantics {
                        contentDescription =
                            "继续给${continuation.contactLabel}说话，剩余${continuation.secondsRemaining}秒，每条消息仍需确认"
                    },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1B5E20),
                    contentColor = Color.White,
                ),
                onClick = onContinueMessage,
            ) {
                Text(
                    "✉ 继续给${continuation.contactLabel}说话（${continuation.secondsRemaining}秒）",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Text("每条新消息都要重新录音、听完整复述并再次确认")
        }
        when (state.stage) {
            TaskStage.CHECKING_CONSENT -> Text("正在读取任务语音授权，请稍等")
            TaskStage.CONSENT_REQUIRED -> TaskAudioConsentSection(onGrantTaskAudioConsent)
            TaskStage.SAVING_CONSENT -> Text("正在保存您的选择，请稍等")
            TaskStage.READY -> BigButton("开始说话", onStartTask)
            TaskStage.RECORDING_TASK -> BigButton("说完了", onStopTask)
            TaskStage.RECORDING_CONFIRMATION -> BigButton("安全指令说完了", onStopConfirmation)
            TaskStage.SUBMITTING_TASK, TaskStage.MATCHING_CONFIRMATION ->
                Text("请稍等，不要退出页面")
            TaskStage.REHEARSING -> Text("请先听完，播放完成前不能确认")
            TaskStage.REHEARSAL_FAILED -> {
                BigButton("重新播放完整复述", onRetryRehearsal)
                BigButton("重新说完整任务", onRepeatTask)
            }
            TaskStage.FAILED -> BigButton("重新开始", onRetry)
            else -> Unit
        }
        if (state.canRestartSimulation()) {
            BigButton("再体验一次", onRetry)
        }
        state.session?.spokenSummary?.let { summary ->
            Text("请听完整复述", style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.headlineSmall)
        }
        val actions = state.session?.allowedActions.orEmpty()
        state.session?.candidates.orEmpty().forEach { candidate ->
            if (AllowedAction.SELECT_CANDIDATE in actions) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelectCandidate(candidate.candidateId) },
                ) {
                    Text("选择 ${candidate.contact.displayName.ifBlank { candidate.contact.alias }}")
                }
            }
        }
        if (state.stage == TaskStage.ACTIVE) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                onClick = onReplaySummary,
            ) {
                Text("再听一遍完整复述", style = MaterialTheme.typography.bodyLarge)
            }
            if (AllowedAction.RETRY in actions) {
                val repeatLabel = if (
                    state.session?.state == TaskState.NEEDS_CONTENT_REPEAT
                ) {
                    "重新说消息内容"
                } else {
                    "重新说完整任务"
                }
                BigButton(repeatLabel, onRepeatTask)
            }
            if (AllowedAction.CONFIRM_SEND in actions) {
                BigButton("说发送确认指令", { onStartConfirmation(ConfirmationAction.CONFIRM_SEND) })
            }
            if (AllowedAction.CONFIRM_CALL in actions) {
                BigButton("说通话确认指令", { onStartConfirmation(ConfirmationAction.CONFIRM_CALL) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (AllowedAction.REJECT in actions) {
                    OutlinedButton(onClick = { onStartConfirmation(ConfirmationAction.REJECT) }) {
                        Text("说拒绝指令")
                    }
                }
                if (AllowedAction.CANCEL in actions) {
                    OutlinedButton(onClick = { onStartConfirmation(ConfirmationAction.CANCEL) }) {
                        Text("说取消指令")
                    }
                }
            }
        }
        if (state.stage in CANCELLABLE_STAGES) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                onClick = onCancelTask,
            ) {
                Text("立即取消本次任务", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (state.guardianResumeAvailable && state.stage in RESUMABLE_STAGES) {
            BigButton("返回并恢复小友守护", onResumeGuardian)
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            onClick = onBack,
        ) {
            Text("返回首页")
        }
    }
}

@Composable
private fun TaskAudioConsentSection(onGrant: () -> Unit) {
    Text(
        "允许后，小友只处理您本次说出的联系人、动作和消息内容。每次发送或通话前，仍要完整复述并由您说安全指令确认。",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        "任务语音不会用于通用模型训练；不同意时不会录音、上传或执行任何微信操作，本页面也不会替您自动同意。",
        style = MaterialTheme.typography.bodyLarge,
    )
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        onClick = onGrant,
    ) {
        Text("同意处理任务语音", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun BigButton(text: String, onClick: () -> Unit) {
    Button(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), onClick = onClick) {
        Text(text, style = MaterialTheme.typography.titleLarge)
    }
}

private val CANCELLABLE_STAGES = setOf(
    TaskStage.RECORDING_TASK,
    TaskStage.SUBMITTING_TASK,
    TaskStage.REHEARSING,
    TaskStage.REHEARSAL_FAILED,
    TaskStage.ACTIVE,
    TaskStage.RECORDING_CONFIRMATION,
    TaskStage.MATCHING_CONFIRMATION,
)

private val RESUMABLE_STAGES = setOf(
    TaskStage.COMPLETED,
    TaskStage.CANCELLED,
    TaskStage.FAILED,
)
