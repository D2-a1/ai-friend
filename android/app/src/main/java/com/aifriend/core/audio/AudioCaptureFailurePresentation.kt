package com.aifriend.core.audio

/**
 * 面向录音页面的稳定中文失败说明。
 *
 * 原始异常只用于本地诊断，不能直接显示给用户，避免暴露英文系统信息或实现细节。
 */
data class AudioCaptureFailurePresentation(
    val userMessage: String,
    val permissionRecoveryRequired: Boolean,
)

/** 把录音异常转换成四条录音业务链共用的可恢复中文结果。 */
fun Throwable.toAudioCaptureFailurePresentation(
    unknownMessage: String = "暂时无法启动录音，请稍后再试",
): AudioCaptureFailurePresentation {
    val failure = (this as? AudioCaptureException)?.failure
    return when (failure) {
        AudioCaptureFailure.ALREADY_RECORDING -> AudioCaptureFailurePresentation(
            userMessage = "上一次录音尚未结束，请返回首页后重新开始",
            permissionRecoveryRequired = false,
        )
        AudioCaptureFailure.PERMISSION_DENIED -> AudioCaptureFailurePresentation(
            userMessage = "麦克风权限已关闭，请先在系统设置中允许",
            permissionRecoveryRequired = true,
        )
        AudioCaptureFailure.AUDIO_FOCUS_DENIED -> AudioCaptureFailurePresentation(
            userMessage = "麦克风正在被通话或其他应用使用，请稍后再试",
            permissionRecoveryRequired = false,
        )
        AudioCaptureFailure.DEVICE_UNAVAILABLE -> AudioCaptureFailurePresentation(
            userMessage = "当前设备无法启动麦克风，请稍后再试",
            permissionRecoveryRequired = false,
        )
        AudioCaptureFailure.STORAGE_UNAVAILABLE -> AudioCaptureFailurePresentation(
            userMessage = "手机暂时无法准备录音空间，请稍后再试",
            permissionRecoveryRequired = false,
        )
        AudioCaptureFailure.READ_FAILED -> AudioCaptureFailurePresentation(
            userMessage = "录音被中断，请重新录制",
            permissionRecoveryRequired = false,
        )
        AudioCaptureFailure.NO_ACTIVE_RECORDING -> AudioCaptureFailurePresentation(
            userMessage = "录音已被系统中断，请重新录制",
            permissionRecoveryRequired = false,
        )
        null -> AudioCaptureFailurePresentation(
            userMessage = unknownMessage,
            permissionRecoveryRequired = false,
        )
    }
}
