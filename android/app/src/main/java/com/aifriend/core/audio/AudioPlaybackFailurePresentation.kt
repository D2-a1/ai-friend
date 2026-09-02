package com.aifriend.core.audio

/** 用户当前正在收听的内容类型。 */
enum class AudioPlaybackContent {
    RECORDING,
    TASK_REHEARSAL,
}

/**
 * 把播放异常转换成固定中文说明。
 *
 * 底层异常可能包含设备或系统英文信息，只用于本地诊断，不直接进入界面。
 */
fun Throwable.toAudioPlaybackUserMessage(content: AudioPlaybackContent): String = when (content) {
    AudioPlaybackContent.RECORDING -> "录音没有播放完整，请重新试听"
    AudioPlaybackContent.TASK_REHEARSAL -> "完整复述没有播放完，请重新播放"
}
