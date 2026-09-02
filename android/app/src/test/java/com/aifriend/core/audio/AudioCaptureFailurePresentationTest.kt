package com.aifriend.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 录音失败的中文展示与权限恢复分类测试。 */
class AudioCaptureFailurePresentationTest {

    @Test
    fun permissionFailureRequiresSettingsRecovery() {
        val presentation = AudioCaptureException(
            AudioCaptureFailure.PERMISSION_DENIED,
            "system detail",
        ).toAudioCaptureFailurePresentation()

        assertTrue(presentation.permissionRecoveryRequired)
        assertEquals("麦克风权限已关闭，请先在系统设置中允许", presentation.userMessage)
    }

    @Test
    fun microphoneBusyReturnsRetryableChineseMessage() {
        val presentation = AudioCaptureException(
            AudioCaptureFailure.AUDIO_FOCUS_DENIED,
            "system detail",
        ).toAudioCaptureFailurePresentation()

        assertFalse(presentation.permissionRecoveryRequired)
        assertEquals("麦克风正在被通话或其他应用使用，请稍后再试", presentation.userMessage)
    }

    @Test
    fun deviceAndStorageFailuresHaveDistinctChineseMessages() {
        val device = AudioCaptureException(
            AudioCaptureFailure.DEVICE_UNAVAILABLE,
            "device failed",
        ).toAudioCaptureFailurePresentation()
        val storage = AudioCaptureException(
            AudioCaptureFailure.STORAGE_UNAVAILABLE,
            "storage failed",
        ).toAudioCaptureFailurePresentation()

        assertEquals("当前设备无法启动麦克风，请稍后再试", device.userMessage)
        assertEquals("手机暂时无法准备录音空间，请稍后再试", storage.userMessage)
    }

    @Test
    fun unknownFailureNeverLeaksRawEnglishMessage() {
        val presentation = IllegalStateException("native recorder exploded")
            .toAudioCaptureFailurePresentation()

        assertFalse(presentation.permissionRecoveryRequired)
        assertEquals("暂时无法启动录音，请稍后再试", presentation.userMessage)
    }

    @Test
    fun readFailureAndMissingActiveRecordingRequireFreshRecording() {
        val readFailure = AudioCaptureException(
            AudioCaptureFailure.READ_FAILED,
            "read returned error",
        ).toAudioCaptureFailurePresentation()
        val missingRecording = AudioCaptureException(
            AudioCaptureFailure.NO_ACTIVE_RECORDING,
            "session disappeared",
        ).toAudioCaptureFailurePresentation()

        assertEquals("录音被中断，请重新录制", readFailure.userMessage)
        assertEquals("录音已被系统中断，请重新录制", missingRecording.userMessage)
        assertFalse(readFailure.permissionRecoveryRequired)
        assertFalse(missingRecording.permissionRecoveryRequired)
    }
}
