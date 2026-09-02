package com.aifriend.feature.collection

import com.aifriend.contract.model.ConsentDecision
import com.aifriend.contract.model.VoiceCollectionCategory
import com.aifriend.contract.model.VoiceCollectionEnvironment
import com.aifriend.contract.model.VoiceCollectionReviewStatus
import com.aifriend.contract.model.VoiceCollectionSample
import com.aifriend.contract.model.VoiceCollectionStatus
import com.aifriend.contract.model.VoiceCollectionTrainingAuthorization
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 封闭测试采集固定提示与非敏感状态测试。 */
class VoiceCollectionUiStateTest {

    @Test
    fun `sample labels use explicit Chinese category names`() {
        VOICE_COLLECTION_PROMPTS.forEach { prompt ->
            val label = sampleLabel(prompt.code)

            assertTrue(label.any { it in '\u4e00'..'\u9fff' })
            assertFalse(label.any { it in 'A'..'Z' || it in 'a'..'z' })
            assertFalse(label.contains(prompt.category.name))
        }
        assertEquals("测试样本", sampleLabel("unknown_prompt"))
    }

    @Test
    fun `fixed prompts cover five bounded categories and keep free alias review empty`() {
        assertEquals(5, VOICE_COLLECTION_PROMPTS.size)
        assertEquals(VoiceCollectionCategory.entries.toSet(),
            VOICE_COLLECTION_PROMPTS.map { it.category }.toSet())
        assertTrue(VOICE_COLLECTION_PROMPTS.all { it.code.matches(Regex("[a-z0-9_]{3,64}")) })
        assertEquals("", VOICE_COLLECTION_PROMPTS.single {
            it.category == VoiceCollectionCategory.CONTACT_ALIAS
        }.defaultTranscript)
        assertFalse(VoiceCollectionUiState()::class.java.declaredFields.any { field ->
            field.name.contains("audio", ignoreCase = true) ||
                field.name.contains("bytes", ignoreCase = true)
        })
        assertFalse(VoiceCollectionUiState().reviewPlaybackCompleted)
    }

    @Test
    fun `training authorization stays sample scoped and updates server version`() {
        val initial = VoiceCollectionUiState(samples = listOf(sample(false, 3L)))

        val pendingGrant = initial.requestTrainingAuthorization(SAMPLE_ID)
        assertEquals(ConsentDecision.GRANTED, pendingGrant.pendingTrainingDecision)

        val granted = pendingGrant.applyTrainingAuthorization(
            VoiceCollectionTrainingAuthorization(
                sampleId = SAMPLE_ID,
                trainingEligible = true,
                version = 4L,
                decidedAt = NOW,
            ),
        )
        assertTrue(granted.trainingConsentGranted)
        assertTrue(granted.samples.single().trainingEligible)
        assertEquals(4L, granted.samples.single().version)
        assertEquals(
            ConsentDecision.REVOKED,
            granted.requestTrainingAuthorization(SAMPLE_ID).pendingTrainingDecision,
        )
    }

    @Test
    fun `unknown sample cannot open training authorization confirmation`() {
        val state = VoiceCollectionUiState(samples = listOf(sample(false, 0L)))

        assertEquals(state, state.requestTrainingAuthorization("vs_ffffffffffffffffffffffffffffffff"))
    }

    @Test
    fun `pending manual review cannot open training authorization confirmation`() {
        val pending = VoiceCollectionUiState(
            samples = listOf(sample(false, 0L, VoiceCollectionReviewStatus.PENDING)),
        )

        assertEquals(pending, pending.requestTrainingAuthorization(SAMPLE_ID))
    }

    @Test
    fun `microphone denial preserves collection state and opens recovery entry`() {
        val original = VoiceCollectionUiState(
            stage = VoiceCollectionStage.READY,
            promptIndex = 3,
            recordedDurationMs = 2_100,
        )

        val denied = original.withMicrophonePermissionDenied()

        assertEquals(original.stage, denied.stage)
        assertEquals(original.promptIndex, denied.promptIndex)
        assertEquals(original.recordedDurationMs, denied.recordedDurationMs)
        assertTrue(denied.microphonePermissionRecoveryRequired)
        assertEquals("需要允许麦克风权限才能参与语音采集", denied.errorMessage)
    }

    private fun sample(
        trainingEligible: Boolean,
        version: Long,
        reviewStatus: VoiceCollectionReviewStatus = VoiceCollectionReviewStatus.CONFIRMED,
    ) = VoiceCollectionSample(
        sampleId = SAMPLE_ID,
        category = VoiceCollectionCategory.WAKE_WORD,
        promptCode = "wake_xiaoyou_01",
        environment = VoiceCollectionEnvironment.QUIET,
        dialectCode = "zh-Hans-CN-x-wugang",
        status = VoiceCollectionStatus.ACTIVE,
        reviewStatus = reviewStatus,
        trainingEligible = trainingEligible,
        reviewedAt = NOW,
        retentionUntil = NOW.plusDays(30),
        version = version,
        createdAt = NOW,
    )

    private companion object {
        const val SAMPLE_ID = "vs_0123456789abcdef0123456789abcdef"
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-08-24T10:00:00Z")
    }
}
