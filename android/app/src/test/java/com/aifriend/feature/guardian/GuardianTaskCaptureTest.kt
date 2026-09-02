package com.aifriend.feature.guardian

import com.aifriend.core.audio.WavPcmCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 唤醒后纯内存任务录音的静音、上限与清理测试。 */
class GuardianTaskCaptureTest {

    @Test
    fun fiveSecondsWithoutSpeechTimesOutAndCannotProduceAudio() {
        val capture = GuardianTaskCapture()
        val silence = ShortArray(1_600)
        capture.start()

        repeat(49) {
            assertEquals(GuardianCaptureBoundary.CONTINUE, capture.append(silence, silence.size))
        }
        assertEquals(
            GuardianCaptureBoundary.NO_SPEECH_TIMEOUT,
            capture.append(silence, silence.size),
        )
        assertThrows(IllegalStateException::class.java, capture::finish)
    }

    @Test
    fun trailingSilenceCompletesAndKeepsOnlySmallTail() {
        val capture = GuardianTaskCapture()
        val voice = ShortArray(1_600) { 2_000 }
        val silence = ShortArray(1_600)
        capture.start()

        repeat(5) { capture.append(voice, voice.size) }
        repeat(11) {
            assertEquals(GuardianCaptureBoundary.CONTINUE, capture.append(silence, silence.size))
        }
        assertEquals(
            GuardianCaptureBoundary.UTTERANCE_COMPLETE,
            capture.append(silence, silence.size),
        )

        val audio = capture.finish()
        try {
            assertEquals(750, audio.durationMs)
            assertEquals("RIFF", audio.wavBytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertTrue(audio.wavBytes.size > WavPcmCodec.HEADER_SIZE)
        } finally {
            audio.clear()
        }
    }

    @Test
    fun configuredMaximumStopsCaptureAndClearRemovesSnapshot() {
        val capture = GuardianTaskCapture(
            maximumDurationMs = 1_000,
            initialSilenceMs = 2_000,
            trailingSilenceMs = 2_000,
            minimumSpeechMs = 100,
        )
        val voice = ShortArray(1_600) { 2_000 }
        capture.start()

        repeat(9) {
            assertEquals(GuardianCaptureBoundary.CONTINUE, capture.append(voice, voice.size))
        }
        assertEquals(
            GuardianCaptureBoundary.MAXIMUM_REACHED,
            capture.append(voice, voice.size),
        )
        capture.clear()

        assertThrows(IllegalStateException::class.java, capture::finish)
    }
}
