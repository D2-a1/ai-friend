package com.aifriend.feature.guardian

import com.aifriend.contract.model.ContactAlias
import com.aifriend.contract.model.SafetyCommandType
import com.aifriend.contract.model.VoiceTemplateSummary
import com.aifriend.core.audio.VoiceTemplateRecordingNormalizer
import com.aifriend.core.audio.WavPcmCodec
import com.aifriend.core.voice.LocalAvailableVoiceTemplate
import com.aifriend.core.voice.LocalTemplateReconciliation
import com.aifriend.core.voice.LocalVoiceTemplateCandidate
import com.aifriend.core.voice.LocalVoiceTemplateCoordinator
import com.aifriend.core.voice.LocalVoiceTemplateEngine
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 固定词义命中后仍必须通过当前 owner 的个人内容模板。 */
class PersonalWakeWordVerifierTest {

    @Test
    fun matchingTemplateAcceptsAndCloseClearsDecryptedMaterial() = runTest {
        val candidate = candidate()
        val engine = FakeEngine(match = true)
        val verifier = LocalPersonalWakeWordVerifier(
            coordinator = FakeCoordinator(candidate),
            engine = engine,
            normalizer = VoiceTemplateRecordingNormalizer(),
        )

        assertTrue(verifier.prepare())
        assertTrue(verifier.matches(sineSamples(1_100)))
        assertTrue(engine.classifyCalls == 1)

        verifier.close()
        assertTrue(candidate.material.all { it == 0.toByte() })
        assertFalse(verifier.matches(sineSamples(1_100)))
    }

    @Test
    fun missingPersonalTemplateFailsClosed() = runTest {
        val verifier = LocalPersonalWakeWordVerifier(
            coordinator = FakeCoordinator(null),
            engine = FakeEngine(match = true),
            normalizer = VoiceTemplateRecordingNormalizer(),
        )

        assertFalse(verifier.prepare())
        assertFalse(verifier.matches(sineSamples(1_100)))
    }

    @Test
    fun naturalDoubleWakeIsSplitAndBothPartsRequirePersonalTemplate() = runTest {
        val engine = FakeEngine(match = true)
        val verifier = LocalPersonalWakeWordVerifier(
            coordinator = FakeCoordinator(candidate()),
            engine = engine,
            normalizer = VoiceTemplateRecordingNormalizer(),
        )

        assertTrue(verifier.prepare())
        assertTrue(verifier.matches(sineSamples(1_400), repetitions = 2))
        assertEquals(2, engine.classifyCalls)
        assertFalse(verifier.matches(sineSamples(1_400), repetitions = 3))
    }

    @Test
    fun fixedPhraseParserAcceptsOnlyOneOrTwoExactWakePhrases() {
        assertEquals(1, guardianWakePhraseRepetitions("{\"text\":\"小 友\"}"))
        assertEquals(2, guardianWakePhraseRepetitions("{\"text\":\"小 友 小 友\"}"))
        assertEquals(0, guardianWakePhraseRepetitions("{\"text\":\"小 友 打 电话\"}"))
        assertEquals(0, guardianWakePhraseRepetitions("{\"text\":\"小 友 小 友 小 友\"}"))
        assertEquals(0, guardianWakePhraseRepetitions("not-json"))
    }

    private class FakeEngine(private val match: Boolean) : LocalVoiceTemplateEngine {
        var classifyCalls = 0
        override fun enroll(firstWav: ByteArray, secondWav: ByteArray) = candidate()
        override fun classify(
            sampleWav: ByteArray,
            templates: Map<String, LocalVoiceTemplateCandidate>,
        ): String? {
            classifyCalls++
            return if (match) templates.keys.single() else null
        }
    }

    private class FakeCoordinator(
        private val wakeCandidate: LocalVoiceTemplateCandidate?,
    ) : LocalVoiceTemplateCoordinator {
        override fun prepare(firstWav: ByteArray, secondWav: ByteArray) = candidate()
        override suspend fun loadWakeWord(): LocalVoiceTemplateCandidate? = wakeCandidate
        override suspend fun persistAlias(
            contactId: String,
            alias: ContactAlias,
            candidate: LocalVoiceTemplateCandidate,
        ) = Unit
        override suspend fun deleteAliasMaterial(aliasId: String) = Unit
        override suspend fun replaceSafetyCommands(
            summaries: List<VoiceTemplateSummary>,
            candidates: Map<SafetyCommandType, LocalVoiceTemplateCandidate>,
        ) = Unit
        override suspend fun reconcile() = LocalTemplateReconciliation(
            emptySet(),
            emptySet(),
            emptySet(),
        )
        override suspend fun loadSafetyCommand(type: SafetyCommandType): LocalAvailableVoiceTemplate? = null
    }

    private companion object {
        fun candidate() = LocalVoiceTemplateCandidate(
            dialectCode = "zh-Hans-CN-x-wugang",
            dialectPackageVersion = "1.0.0",
            modelVersion = "mfcc-dtw-1",
            thresholdVersion = "test-1",
            material = byteArrayOf(1, 2, 3),
        )

        fun sineSamples(durationMs: Int): ShortArray =
            ShortArray(WavPcmCodec.SAMPLE_RATE * durationMs / 1_000) { index ->
                (sin(2.0 * PI * 220.0 * index / WavPcmCodec.SAMPLE_RATE) * 7_000)
                    .toInt()
                    .toShort()
            }
    }
}
