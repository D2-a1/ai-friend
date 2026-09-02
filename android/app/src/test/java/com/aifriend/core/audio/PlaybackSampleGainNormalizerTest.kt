package com.aifriend.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 试听 PCM 有界增益测试。
 *
 * @author codex
 * @since 2026-08-26
 */
class PlaybackSampleGainNormalizerTest {

    @Test
    fun lowLevelRecordingIsAmplifiedWithinMaximumGain() {
        val samples = shortArrayOf(-1_800, 0, 1_800)

        val gain = PlaybackSampleGainNormalizer.applyInPlace(samples)

        assertEquals(8.0, gain, 0.0)
        assertArrayEquals(shortArrayOf(-14_400, 0, 14_400), samples)
    }

    @Test
    fun alreadyAudibleRecordingIsNotAmplifiedOrAttenuated() {
        val samples = shortArrayOf(-24_000, 0, 24_000)
        val original = samples.copyOf()

        val gain = PlaybackSampleGainNormalizer.applyInPlace(samples)

        assertEquals(1.0, gain, 0.0)
        assertArrayEquals(original, samples)
    }

    @Test
    fun silenceRemainsSilence() {
        val samples = ShortArray(16)

        val gain = PlaybackSampleGainNormalizer.applyInPlace(samples)

        assertEquals(1.0, gain, 0.0)
        assertArrayEquals(ShortArray(16), samples)
    }
}
