package com.aifriend.feature.guardian

import com.aifriend.core.feedback.HapticCue
import com.aifriend.core.feedback.HapticFeedbackPort
import org.junit.Assert.assertEquals
import org.junit.Test

/** 守护震动只跟随真实状态转换，不跟随重复事件。 */
class GuardianRuntimeStoreTest {

    @Test
    fun repeatedOrIgnoredEventsDoNotRepeatHapticCue() {
        val haptics = FakeHapticFeedbackPort()
        val store = GuardianRuntimeStore(haptics)

        store.dispatch(GuardianEvent.EnableRequested)
        store.dispatch(GuardianEvent.EnableRequested)
        store.dispatch(GuardianEvent.CaptureStarted)
        store.dispatch(GuardianEvent.WakeWordDetected(1_000L))
        store.dispatch(GuardianEvent.WakeWordDetected(2_000L))
        store.dispatch(GuardianEvent.WakeWordDetected(2_500L))

        assertEquals(
            listOf(HapticCue.NONE, HapticCue.NONE, HapticCue.LISTENING),
            haptics.cues,
        )
    }

    @Test
    fun busyErrorAndShutdownUseFiniteMappedCues() {
        val haptics = FakeHapticFeedbackPort()
        val store = GuardianRuntimeStore(haptics)
        store.dispatch(GuardianEvent.EnableRequested)
        store.dispatch(GuardianEvent.CaptureStarted)

        store.dispatch(GuardianEvent.AudioBecameBusy)
        store.dispatch(GuardianEvent.AudioBecameAvailable)
        store.dispatch(GuardianEvent.Failed("小友守护已停止"))
        store.dispatch(GuardianEvent.Failed("重复错误"))

        assertEquals(
            listOf(
                HapticCue.NONE,
                HapticCue.NONE,
                HapticCue.BUSY,
                HapticCue.NONE,
                HapticCue.ERROR,
            ),
            haptics.cues,
        )
    }

    private class FakeHapticFeedbackPort : HapticFeedbackPort {
        val cues = mutableListOf<HapticCue>()

        override fun emit(cue: HapticCue) {
            cues += cue
        }

        override fun cancel() = Unit
    }
}
