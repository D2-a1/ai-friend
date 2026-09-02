package com.aifriend.core.voice

import com.aifriend.core.audio.WavPcmCodec
import com.aifriend.feature.task.BasicExperienceTaskContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 基础体验个人模板参数与任务版本上下文的契约测试。 */
class BasicExperienceDialectPackageTest {
    @Test
    fun createsBoundedPersonalTemplatePackageWithoutClaimingSignedModel() {
        val basicPackage = BasicExperienceDialectPackage.create()

        assertEquals("basic-experience", basicPackage.manifest.signatureKeyId)
        assertEquals("MFCC_DTW_V1", basicPackage.manifest.acousticEngine)
        assertEquals(16_000, basicPackage.calibration.sampleRateHz)
        assertTrue(basicPackage.calibration.minimumDurationMs >= 300)
        assertTrue(basicPackage.calibration.maximumDurationMs <= 5_000)
        assertEquals(
            basicPackage.manifest.packageVersion,
            BasicExperienceTaskContext.PACKAGE_VERSION,
        )
        assertEquals(
            basicPackage.manifest.acousticModelVersion,
            BasicExperienceTaskContext.ACOUSTIC_MODEL_VERSION,
        )
    }

    @Test
    fun fixedPersonalParametersProduceAUsableLocalTemplate() {
        val engine = MfccDtwLocalVoiceTemplateEngine {
            BasicExperienceDialectPackage.create()
        }

        val candidate = engine.enroll(
            sineWav(durationMs = 1_200, amplitude = 7_000),
            sineWav(durationMs = 1_250, amplitude = 7_500),
        )

        assertEquals(BasicExperienceDialectPackage.PACKAGE_VERSION, candidate.dialectPackageVersion)
        assertEquals(BasicExperienceDialectPackage.ACOUSTIC_MODEL_VERSION, candidate.modelVersion)
        assertEquals(BasicExperienceDialectPackage.THRESHOLD_VERSION, candidate.thresholdVersion)
        assertTrue(candidate.material.isNotEmpty())
        candidate.clear()
        assertTrue(candidate.material.all { value -> value == 0.toByte() })
    }

    @Test
    fun identicalTemplatesFailTheSameMutualDistinctnessGateAsServer() {
        val engine = MfccDtwLocalVoiceTemplateEngine {
            BasicExperienceDialectPackage.create()
        }
        val candidate = engine.enroll(
            sineWav(durationMs = 1_200, amplitude = 7_000),
            sineWav(durationMs = 1_250, amplitude = 7_500),
        )

        assertTrue(engine.isMutuallyDistinct(candidate, emptyList()))
        assertEquals(false, engine.isMutuallyDistinct(candidate, listOf(candidate)))

        candidate.clear()
    }

    @Test
    fun differentSafetyPhrasesPassRelativeDoubleTakeGate() {
        val engine = MfccDtwLocalVoiceTemplateEngine {
            BasicExperienceDialectPackage.create()
        }
        val first = engine.enroll(
            phraseWav(300, 450, 620, 430),
            phraseWav(301, 451, 621, 431),
        )
        val second = engine.enroll(
            phraseWav(760, 520, 340, 720),
            phraseWav(761, 521, 341, 721),
        )

        assertTrue(engine.isMutuallyDistinct(second, listOf(first)))

        first.clear()
        second.clear()
    }

    @Test
    fun repeatedSafetyPhraseFailsRelativeDoubleTakeGate() {
        val engine = MfccDtwLocalVoiceTemplateEngine {
            BasicExperienceDialectPackage.create()
        }
        val first = engine.enroll(
            phraseWav(300, 450, 620, 430),
            phraseWav(301, 451, 621, 431),
        )
        val repeated = engine.enroll(
            phraseWav(302, 452, 622, 432),
            phraseWav(303, 453, 623, 433),
        )

        assertEquals(false, engine.isMutuallyDistinct(repeated, listOf(first)))

        first.clear()
        repeated.clear()
    }

    private fun phraseWav(vararg frequencies: Int): ByteArray {
        val durationMs = 1_200
        val sampleCount = WavPcmCodec.SAMPLE_RATE * durationMs / 1_000
        val samples = ShortArray(sampleCount) { index ->
            val segment = minOf(frequencies.lastIndex, index * frequencies.size / sampleCount)
            (sin(2.0 * PI * frequencies[segment] * index / WavPcmCodec.SAMPLE_RATE) * 7_000)
                .toInt()
                .toShort()
        }
        val pcm = ByteArray(samples.size * 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
        samples.fill(0)
        return WavPcmCodec.encodeMono16(pcm).also { pcm.fill(0) }
    }

    private fun sineWav(durationMs: Int, amplitude: Int): ByteArray {
        val samples = ShortArray(WavPcmCodec.SAMPLE_RATE * durationMs / 1_000) { index ->
            (sin(2.0 * PI * 220.0 * index / WavPcmCodec.SAMPLE_RATE) * amplitude)
                .toInt()
                .toShort()
        }
        val pcm = ByteArray(samples.size * 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
        samples.fill(0)
        return WavPcmCodec.encodeMono16(pcm).also { bytes -> pcm.fill(0) }
    }
}
