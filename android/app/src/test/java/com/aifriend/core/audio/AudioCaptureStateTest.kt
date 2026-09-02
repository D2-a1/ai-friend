package com.aifriend.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 音频状态基础测试。
 *
 * @author codex
 * @since 2026-07-25
 */
class AudioCaptureStateTest {

    @Test
    fun stoppedMustBeTheSafeDefaultState() {
        assertEquals(AudioCaptureState.STOPPED, AudioCaptureState.valueOf("STOPPED"))
    }
}
