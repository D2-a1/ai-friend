package com.aifriend.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 称呼录音本地质量预检测试。
 *
 * @author codex
 * @since 2026-08-12
 */
class AliasRecordingQualityAnalyzerTest {

    private val analyzer = AliasRecordingQualityAnalyzer()

    @Test
    fun clearSpeechLikeSignalPassesCoarsePrecheck() {
        val result = analyzer.analyze(sineAudio(durationMs = 1_500, amplitude = 8_000))

        assertTrue(result is AliasRecordingQualityResult.Passed)
        assertEquals(1_500, (result as AliasRecordingQualityResult.Passed).durationMs)
    }

    @Test
    fun recordingShorterThanOneSecondIsRejected() {
        val result = analyzer.analyze(sineAudio(durationMs = 800, amplitude = 8_000))

        assertEquals(
            AliasRecordingQualityIssue.TOO_SHORT,
            (result as AliasRecordingQualityResult.Rejected).issue,
        )
    }

    @Test
    fun silenceIsRejectedWithoutUploading() {
        val result = analyzer.analyze(sineAudio(durationMs = 1_500, amplitude = 0))

        assertEquals(
            AliasRecordingQualityIssue.TOO_QUIET,
            (result as AliasRecordingQualityResult.Rejected).issue,
        )
    }

    @Test
    fun lowLevelEmulatorSignalPassesCompatibilityProfileOnly() {
        val lowLevelAudio = sineAudio(durationMs = 1_500, amplitude = 1_800)

        val standardResult = analyzer.analyze(lowLevelAudio)
        val emulatorResult = AliasRecordingQualityAnalyzer.emulatorCompatible()
            .analyze(lowLevelAudio)

        assertEquals(
            AliasRecordingQualityIssue.TOO_QUIET,
            (standardResult as AliasRecordingQualityResult.Rejected).issue,
        )
        assertTrue(emulatorResult is AliasRecordingQualityResult.Passed)
    }

    @Test
    fun silenceIsStillRejectedByEmulatorCompatibilityProfile() {
        val result = AliasRecordingQualityAnalyzer.emulatorCompatible()
            .analyze(sineAudio(durationMs = 1_500, amplitude = 0))

        assertEquals(
            AliasRecordingQualityIssue.TOO_QUIET,
            (result as AliasRecordingQualityResult.Rejected).issue,
        )
    }

    @Test
    fun heavilyClippedRecordingIsRejected() {
        val samples = ShortArray(WavPcmCodec.SAMPLE_RATE * 2) { index ->
            if (index % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE
        }
        val result = analyzer.analyze(capturedAudio(samples))

        assertEquals(
            AliasRecordingQualityIssue.TOO_LOUD,
            (result as AliasRecordingQualityResult.Rejected).issue,
        )
    }

    @Test
    fun malformedWavIsRejectedClosed() {
        val result = analyzer.analyze(CapturedAudio(ByteArray(64), 1_000))

        assertEquals(
            AliasRecordingQualityIssue.INVALID_AUDIO,
            (result as AliasRecordingQualityResult.Rejected).issue,
        )
    }

    private fun sineAudio(durationMs: Int, amplitude: Int): CapturedAudio {
        val sampleCount = WavPcmCodec.SAMPLE_RATE * durationMs / 1_000
        val samples = ShortArray(sampleCount) { index ->
            (sin(2.0 * PI * 220.0 * index / WavPcmCodec.SAMPLE_RATE) * amplitude).toInt().toShort()
        }
        return capturedAudio(samples)
    }

    private fun capturedAudio(samples: ShortArray): CapturedAudio {
        val pcmBytes = ByteArray(samples.size * 2)
        ByteBuffer.wrap(pcmBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .put(samples)
        val wavBytes = WavPcmCodec.encodeMono16(pcmBytes)
        pcmBytes.fill(0)
        return CapturedAudio(
            wavBytes = wavBytes,
            durationMs = samples.size * 1_000 / WavPcmCodec.SAMPLE_RATE,
        )
    }
}
