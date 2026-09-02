package com.aifriend.feature.task

import com.aifriend.contract.model.AllowedAction
import com.aifriend.contract.model.ConfirmationAction
import com.aifriend.contract.model.Consent
import com.aifriend.contract.model.ConsentDecision
import com.aifriend.contract.model.ConsentType
import com.aifriend.contract.model.TaskSession
import com.aifriend.contract.model.TaskState

/** 当前任务页面阶段。 */
enum class TaskStage {
    CHECKING_CONSENT,
    CONSENT_REQUIRED,
    SAVING_CONSENT,
    READY,
    RECORDING_TASK,
    SUBMITTING_TASK,
    REHEARSING,
    REHEARSAL_FAILED,
    ACTIVE,
    RECORDING_CONFIRMATION,
    MATCHING_CONFIRMATION,
    COMPLETED,
    CANCELLED,
    FAILED,
}

/**
 * 不包含原始录音、模板正文或微信明文标识的任务 UI 状态。
 *
 * @author codex
 * @since 2026-08-13
 */
data class TaskUiState(
    val stage: TaskStage = TaskStage.READY,
    val session: TaskSession? = null,
    val pendingConfirmationAction: ConfirmationAction? = null,
    val continuation: MessageContinuationUi? = null,
    val guardianResumeAvailable: Boolean = false,
    val microphonePermissionRecoveryRequired: Boolean = false,
    val statusMessage: String = "点击按钮，说出要联系谁和要做什么",
    val errorMessage: String? = null,
)

/** 只有当前任务语音政策的明确同意才允许进入录音准备页。 */
internal fun Iterable<Consent>.hasTaskAudioConsent(policyVersion: String): Boolean = any { consent ->
    consent.type == ConsentType.TASK_AUDIO &&
        consent.decision == ConsentDecision.GRANTED &&
        consent.policyVersion == policyVersion
}

/** 麦克风权限被拒绝时保留当前会话和阶段，只更新可见提示。 */
internal fun TaskUiState.withMicrophonePermissionDenied(): TaskUiState = copy(
    microphonePermissionRecoveryRequired = true,
    statusMessage = "需要允许麦克风权限才能录音",
    errorMessage = "未允许麦克风权限；本次没有录音、上传或执行任何任务",
)

/** 复述摘要与当前会话精确一致后，才允许采集动作型确认。 */
internal fun TaskUiState.canStartConfirmation(
    action: ConfirmationAction,
    rehearsedSummaryHash: String?,
): Boolean {
    val current = session ?: return false
    val requiredAction = when (action) {
        ConfirmationAction.CONFIRM_SEND -> AllowedAction.CONFIRM_SEND
        ConfirmationAction.CONFIRM_CALL -> AllowedAction.CONFIRM_CALL
        ConfirmationAction.REJECT -> AllowedAction.REJECT
        ConfirmationAction.CANCEL -> AllowedAction.CANCEL
    }
    return stage == TaskStage.ACTIVE && current.state == TaskState.AWAITING_CONFIRMATION &&
        !current.summaryHash.isNullOrBlank() && current.summaryHash == rehearsedSummaryHash &&
        requiredAction in current.allowedActions
}

/** 只有 Debug 个人体验的模拟终态允许在当前页面直接开始新一轮。 */
internal fun TaskUiState.canRestartSimulation(): Boolean =
    stage == TaskStage.COMPLETED && session?.state == TaskState.SIMULATED

/** 把服务端有限任务状态转换为老人可理解且不夸大的提示。 */
internal fun TaskState.userMessage(): String = when (this) {
    TaskState.CREATED -> "任务已创建，正在准备处理"
    TaskState.PROCESSING -> "正在识别并匹配联系人"
    TaskState.AWAITING_SELECTION -> "没有唯一匹配联系人，请选择后再确认"
    TaskState.AWAITING_CONFIRMATION -> "请先听完整复述，再说对应的个人安全指令"
    TaskState.EXECUTING -> "正在执行已确认的任务，请不要重复操作"
    TaskState.REJECTED -> "已拒绝，本次任务不会执行"
    TaskState.CANCELLED -> "已取消，本次任务不会执行"
    TaskState.SIMULATED -> "个人体验流程已完成，没有调用微信，也没有发送消息或发起通话"
    TaskState.NEEDS_CONTENT_REPEAT -> "请重新说要告诉亲友的内容"
    TaskState.NEEDS_RETRY -> "请重新说完整任务"
    TaskState.COMPLETED -> "微信渠道已返回可验证完成状态"
    TaskState.PARTIAL -> "仅部分交给微信，不会自动重发"
    TaskState.FAILED -> "微信渠道没有完成，不会自动重试"
}
