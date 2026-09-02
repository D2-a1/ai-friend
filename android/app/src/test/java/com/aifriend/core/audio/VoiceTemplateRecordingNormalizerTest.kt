package com.aifriend.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 称呼与固定安全指令短语音裁剪回归。 */
class VoiceTemplateRecordingNormalizerTest {

    private val normalizer = VoiceTemplateRecordingNormalizer()

    @Test
    fun naturalShortPhraseBelowOldOneSecondLimitPasses() {
        val original = capturedAudio(
            totalDurationMs = 600,
            speechStartMs = 40,
            speechEndMs = 560,
        )

        val result = normalizer.normalize(original)

        assertTrue(result is VoiceTemplateRecordingResult.Passed)
        result as VoiceTemplateRecordingResult.Passed
        assertTrue(result.durationMs in 300..600)
        assertEquals("RIFF", result.audio.wavBytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        result.audio.clear()
        original.clear()
    }

    @Test
    fun leadingAndTrailingSilenceAreRemovedBeforeQualityCheck() {
        val original = capturedAudio(
            totalDurationMs = 2_000,
            speechStartMs = 700,
            speechEndMs = 1_250,
        )

        val result = normalizer.normalize(original)

        assertTrue(result is VoiceTemplateRecordingResult.Passed)
        result as VoiceTemplateRecordingResult.Passed
        assertTrue(result.durationMs < 1_000)
        assertTrue(result.durationMs >= 300)
        result.audio.clear()
        original.clear()
    }

    @Test
    fun actualSpeechUnderThreeHundredMillisecondsIsRejectedPrecisely() {
        val original = capturedAudio(
            totalDurationMs = 1_000,
            speechStartMs = 400,
            speechEndMs = 620,
        )

        val result = normalizer.normalize(original)

        assertEquals(
            AliasRecordingQualityIssue.TOO_SHORT,
            (result as VoiceTemplateRecordingResult.Rejected).issue,
        )
        assertTrue(result.message.contains("有效发音太短"))
        original.clear()
    }

    @Test
    fun silenceIsRejectedAsTooQuiet() {
        val original = capturedAudio(
            totalDurationMs = 800,
            speechStartMs = 0,
            speechEndMs = 0,
        )

        val result = normalizer.normalize(original)

        assertEquals(
            AliasRecordingQualityIssue.TOO_QUIET,
            (result as VoiceTemplateRecordingResult.Rejected).issue,
        )
        original.clear()
    }

    private fun capturedAudio(
        totalDurationMs: Int,
        speechStartMs: Int,
        speechEndMs: Int,
    ): CapturedAudio {
        val sampleCount = WavPcmCodec.SAMPLE_RATE * totalDurationMs / 1_000
        val speechStart = WavPcmCodec.SAMPLE_RATE * speechStartMs / 1_000
        val speechEnd = WavPcmCodec.SAMPLE_RATE * speechEndMs / 1_000
        val samples = ShortArray(sampleCount) { index ->
            if (index in speechStart until speechEnd) {
                (sin(2.0 * PI * 220.0 * index / WavPcmCodec.SAMPLE_RATE) * 7_000)
                    .toInt()
                    .toShort()
            } else {
                0
            }
        }
        val pcmBytes = ByteArray(samples.size * Short.SIZE_BYTES)
        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
        val wav = WavPcmCodec.encodeMono16(pcmBytes)
        samples.fill(0)
        pcmBytes.fill(0)
        return CapturedAudio(wav, totalDurationMs)
    }
}
