package com.aifriend.feature.task

import com.aifriend.contract.model.TaskState
import com.aifriend.core.feedback.AccessibleFeedbackPresentation
import com.aifriend.core.feedback.FeedbackSymbol
import com.aifriend.core.feedback.FeedbackTone
import com.aifriend.core.feedback.HapticCue

/** 将任务页有限阶段映射为不依赖颜色的适老化反馈。 */
internal fun TaskUiState.feedbackPresentation(): AccessibleFeedbackPresentation = when (stage) {
    TaskStage.CHECKING_CONSENT,
    TaskStage.SAVING_CONSENT,
    -> presentation(
        FeedbackSymbol.PROCESSING,
        FeedbackTone.ACTIVE,
        "正在准备",
        "正在核对当前任务语音授权",
        HapticCue.NONE,
    )
    TaskStage.CONSENT_REQUIRED -> presentation(
        FeedbackSymbol.ATTENTION,
        FeedbackTone.ATTENTION,
        "需要您的同意",
        "请先阅读并决定是否允许处理当前任务语音",
        HapticCue.ATTENTION,
    )
    TaskStage.READY -> presentation(
        FeedbackSymbol.OFF,
        FeedbackTone.NEUTRAL,
        "准备好了",
        "可以开始说联系亲友的任务",
        HapticCue.NONE,
    )
    TaskStage.RECORDING_TASK,
    TaskStage.RECORDING_CONFIRMATION
    -> presentation(
        FeedbackSymbol.LISTENING,
        FeedbackTone.ACTIVE,
        "正在听",
        "正在录音，说完后请点击停止",
        HapticCue.LISTENING,
    )
    TaskStage.SUBMITTING_TASK,
    TaskStage.MATCHING_CONFIRMATION
    -> presentation(
        FeedbackSymbol.PROCESSING,
        FeedbackTone.ACTIVE,
        "正在处理",
        "正在处理当前语音任务",
        HapticCue.NONE,
    )
    TaskStage.REHEARSING -> presentation(
        FeedbackSymbol.PROCESSING,
        FeedbackTone.ATTENTION,
        "正在完整复述",
        "请先听完原声和摘要，暂时不能确认",
        HapticCue.NONE,
    )
    TaskStage.REHEARSAL_FAILED -> presentation(
        FeedbackSymbol.ERROR,
        FeedbackTone.ERROR,
        "复述未完成",
        "完整复述没有完成，本次任务不能确认",
        HapticCue.ERROR,
        urgent = true,
    )
    TaskStage.ACTIVE -> activePresentation(session?.state)
    TaskStage.COMPLETED -> terminalPresentation(session?.state)
    TaskStage.CANCELLED -> presentation(
        FeedbackSymbol.CANCELLED,
        FeedbackTone.NEUTRAL,
        "已取消",
        "当前任务已取消，不会继续执行",
        HapticCue.CANCELLED,
        urgent = true,
    )
    TaskStage.FAILED -> presentation(
        FeedbackSymbol.ERROR,
        FeedbackTone.ERROR,
        "已停止",
        "当前任务遇到错误并已停止",
        HapticCue.ERROR,
        urgent = true,
    )
}

private fun activePresentation(state: TaskState?): AccessibleFeedbackPresentation = when (state) {
    TaskState.CREATED,
    TaskState.PROCESSING,
    -> processingPresentation(
        "正在处理",
        "正在识别并准备当前任务，请稍等",
    )
    TaskState.AWAITING_SELECTION -> attentionPresentation(
        "请选联系人",
        "联系人不唯一，请先选择",
    )
    TaskState.AWAITING_CONFIRMATION -> attentionPresentation(
        "请说安全指令",
        "完整复述已结束，请说对应的个人安全指令",
    )
    TaskState.NEEDS_CONTENT_REPEAT -> attentionPresentation(
        "请重说消息内容",
        "消息内容不完整，请全新重录或取消",
    )
    TaskState.NEEDS_RETRY -> attentionPresentation(
        "请重说完整任务",
        "当前任务不可靠，请全新重录或取消",
    )
    TaskState.EXECUTING -> processingPresentation(
        "正在执行",
        "已确认的任务正在执行，请不要重复操作",
    )
    TaskState.REJECTED,
    TaskState.CANCELLED,
    TaskState.SIMULATED,
    TaskState.COMPLETED,
    TaskState.PARTIAL,
    TaskState.FAILED,
    -> terminalPresentation(state)
    null -> presentation(
        FeedbackSymbol.OFF,
        FeedbackTone.NEUTRAL,
        "任务未就绪",
        "当前没有可处理的任务",
        HapticCue.NONE,
    )
}

private fun terminalPresentation(state: TaskState?): AccessibleFeedbackPresentation = when (state) {
    TaskState.COMPLETED -> presentation(
        FeedbackSymbol.SUCCESS,
        FeedbackTone.SUCCESS,
        "本次已结束",
        "当前任务已进入可验证完成状态",
        HapticCue.SUCCESS,
    )
    TaskState.PARTIAL -> presentation(
        FeedbackSymbol.ATTENTION,
        FeedbackTone.ATTENTION,
        "仅完成一部分",
        "当前任务只有部分可验证步骤完成，不会自动重试",
        HapticCue.ATTENTION,
        urgent = true,
    )
    TaskState.REJECTED -> presentation(
        FeedbackSymbol.CANCELLED,
        FeedbackTone.NEUTRAL,
        "已拒绝",
        "当前任务已拒绝，不会执行",
        HapticCue.CANCELLED,
    )
    TaskState.CANCELLED -> presentation(
        FeedbackSymbol.CANCELLED,
        FeedbackTone.NEUTRAL,
        "已取消",
        "当前任务已取消，不会继续执行",
        HapticCue.CANCELLED,
        urgent = true,
    )
    TaskState.SIMULATED -> presentation(
        FeedbackSymbol.SUCCESS,
        FeedbackTone.SUCCESS,
        "体验已完成",
        "个人体验已完成，没有调用微信",
        HapticCue.SUCCESS,
    )
    TaskState.FAILED -> presentation(
        FeedbackSymbol.ERROR,
        FeedbackTone.ERROR,
        "没有完成",
        "当前任务没有完成，不会自动重试",
        HapticCue.ERROR,
        urgent = true,
    )
    TaskState.CREATED,
    TaskState.PROCESSING,
    TaskState.AWAITING_SELECTION,
    TaskState.AWAITING_CONFIRMATION,
    TaskState.EXECUTING,
    TaskState.NEEDS_CONTENT_REPEAT,
    TaskState.NEEDS_RETRY,
    -> activePresentation(state)
    null -> presentation(
        FeedbackSymbol.OFF,
        FeedbackTone.NEUTRAL,
        "任务未就绪",
        "当前没有可处理的任务",
        HapticCue.NONE,
    )
}

private fun processingPresentation(
    label: String,
    accessibilityLabel: String,
): AccessibleFeedbackPresentation = presentation(
    FeedbackSymbol.PROCESSING,
    FeedbackTone.ACTIVE,
    label,
    accessibilityLabel,
    HapticCue.NONE,
)

private fun attentionPresentation(
    label: String,
    accessibilityLabel: String,
): AccessibleFeedbackPresentation = presentation(
    FeedbackSymbol.ATTENTION,
    FeedbackTone.ATTENTION,
    label,
    accessibilityLabel,
    HapticCue.ATTENTION,
)

private fun presentation(
    symbol: FeedbackSymbol,
    tone: FeedbackTone,
    label: String,
    accessibilityLabel: String,
    hapticCue: HapticCue,
    urgent: Boolean = false,
): AccessibleFeedbackPresentation = AccessibleFeedbackPresentation(
    symbol = symbol,
    tone = tone,
    label = label,
    accessibilityLabel = accessibilityLabel,
    hapticCue = hapticCue,
    urgent = urgent,
)
