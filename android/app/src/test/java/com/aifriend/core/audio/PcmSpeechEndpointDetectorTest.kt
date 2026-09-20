package com.aifriend.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmSpeechEndpointDetectorTest {

    @Test
    fun completesAfterSpeechAndContinuousTrailingSilence() {
        val detector = detector()

        assertEquals(SpeechEndpointBoundary.CONTINUE, detector.appendPcm16Le(pcm(200, 1_000)))
        assertEquals(SpeechEndpointBoundary.CONTINUE, detector.appendPcm16Le(pcm(200, 0)))
        assertEquals(
            SpeechEndpointBoundary.UTTERANCE_COMPLETE,
            detector.appendPcm16Le(pcm(100, 0)),
        )
    }

    @Test
    fun ordinaryPauseDoesNotFinishAndSpeechResetsTrailingSilence() {
        val detector = detector()

        detector.appendPcm16Le(pcm(200, 1_000))
        assertEquals(SpeechEndpointBoundary.CONTINUE, detector.appendPcm16Le(pcm(250, 0)))
        assertEquals(SpeechEndpointBoundary.CONTINUE, detector.appendPcm16Le(pcm(100, 1_000)))
        assertEquals(SpeechEndpointBoundary.CONTINUE, detector.appendPcm16Le(pcm(250, 0)))
    }

    @Test
    fun silenceWithoutSpeechEndsAsNoSpeechTimeout() {
        val detector = detector()

        assertEquals(
            SpeechEndpointBoundary.NO_SPEECH_TIMEOUT,
            detector.appendPcm16Le(pcm(500, 0)),
        )
    }

    @Test
    fun maximumDurationIsAlwaysBounded() {
        val detector = detector()

        assertEquals(
            SpeechEndpointBoundary.MAXIMUM_REACHED,
            detector.appendPcm16Le(pcm(2_000, 1_000)),
        )
    }

    private fun detector() = PcmSpeechEndpointDetector(
        sampleRate = 1_000,
        maximumDurationMs = 2_000,
        initialSilenceMs = 500,
        trailingSilenceMs = 300,
        minimumSpeechMs = 100,
        minimumRms = 100.0,
    )

    private fun pcm(sampleCount: Int, amplitude: Int): ByteArray =
        ByteArray(sampleCount * Short.SIZE_BYTES).also { bytes ->
            repeat(sampleCount) { index ->
                bytes[index * 2] = (amplitude and 0xFF).toByte()
                bytes[index * 2 + 1] = (amplitude shr 8).toByte()
            }
        }
}
