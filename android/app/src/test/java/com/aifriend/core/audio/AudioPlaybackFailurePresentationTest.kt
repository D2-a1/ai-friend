package com.aifriend.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** 播放失败固定中文说明测试。 */
class AudioPlaybackFailurePresentationTest {

    @Test
    fun recordingFailureNeverLeaksRawSystemMessage() {
        val message = IllegalStateException("native AudioTrack failed")
            .toAudioPlaybackUserMessage(AudioPlaybackContent.RECORDING)

        assertEquals("录音没有播放完整，请重新试听", message)
    }

    @Test
    fun taskRehearsalFailureUsesRetryMessage() {
        val message = AudioPlaybackException("device unavailable")
            .toAudioPlaybackUserMessage(AudioPlaybackContent.TASK_REHEARSAL)

        assertEquals("完整复述没有播放完，请重新播放", message)
    }
}
