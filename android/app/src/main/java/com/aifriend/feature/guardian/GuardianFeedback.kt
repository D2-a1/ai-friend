package com.aifriend.feature.guardian

import com.aifriend.core.feedback.AccessibleFeedbackPresentation
import com.aifriend.core.feedback.FeedbackSymbol
import com.aifriend.core.feedback.FeedbackTone
import com.aifriend.core.feedback.HapticCue

/** 将守护有限状态映射为不依赖颜色的适老化反馈。 */
internal fun GuardianStatus.feedbackPresentation(): AccessibleFeedbackPresentation = when (mode) {
    GuardianMode.GUARDIAN_OFF -> AccessibleFeedbackPresentation(
        FeedbackSymbol.OFF,
        FeedbackTone.NEUTRAL,
        "已关闭",
        "小友守护已关闭",
        HapticCue.NONE,
    )
    GuardianMode.STARTING -> AccessibleFeedbackPresentation(
        FeedbackSymbol.STARTING,
        FeedbackTone.ACTIVE,
        "正在开启",
        "小友守护正在开启",
        HapticCue.NONE,
    )
    GuardianMode.SLEEPING -> AccessibleFeedbackPresentation(
        FeedbackSymbol.WAITING,
        FeedbackTone.NEUTRAL,
        "等待唤醒",
        "小友正在本机等待唤醒",
        HapticCue.NONE,
    )
    GuardianMode.AWAKE_LISTENING -> AccessibleFeedbackPresentation(
        FeedbackSymbol.LISTENING,
        FeedbackTone.ACTIVE,
        "正在听",
        "小友已唤醒，正在听您说话",
        HapticCue.LISTENING,
    )
    GuardianMode.PROCESSING -> AccessibleFeedbackPresentation(
        FeedbackSymbol.PROCESSING,
        FeedbackTone.ACTIVE,
        "正在理解",
        "小友正在本机处理当前任务",
        HapticCue.NONE,
    )
    GuardianMode.TASK_HANDOFF -> AccessibleFeedbackPresentation(
        FeedbackSymbol.WAITING,
        FeedbackTone.NEUTRAL,
        "任务确认中",
        "任务正在前台确认，小友暂停监听",
        HapticCue.NONE,
    )
    GuardianMode.QUESTION_PAUSED -> AccessibleFeedbackPresentation(
        FeedbackSymbol.WAITING,
        FeedbackTone.NEUTRAL,
        "问答使用中",
        "问答正在使用语音，小友暂停监听",
        HapticCue.NONE,
    )
    GuardianMode.WECHAT_BUSY -> AccessibleFeedbackPresentation(
        FeedbackSymbol.BUSY,
        FeedbackTone.ATTENTION,
        "暂停监听",
        "麦克风正被占用，小友暂停监听",
        HapticCue.BUSY,
        urgent = true,
    )
    GuardianMode.ERROR -> AccessibleFeedbackPresentation(
        FeedbackSymbol.ERROR,
        FeedbackTone.ERROR,
        "已停止",
        "小友守护遇到错误并已停止",
        HapticCue.ERROR,
        urgent = true,
    )
}
