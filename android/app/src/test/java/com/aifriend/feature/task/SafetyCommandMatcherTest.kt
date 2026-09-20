package com.aifriend.feature.task

import com.aifriend.contract.model.ConfirmationAction
import com.aifriend.contract.model.ContactAlias
import com.aifriend.contract.model.SafetyCommandType
import com.aifriend.contract.model.VoiceTemplateSummary
import com.aifriend.core.voice.LocalAvailableVoiceTemplate
import com.aifriend.core.voice.LocalTemplateReconciliation
import com.aifriend.core.voice.LocalVoiceTemplateCandidate
import com.aifriend.core.voice.LocalVoiceTemplateCoordinator
import com.aifriend.core.voice.LocalVoiceTemplateEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyCommandMatcherTest {

    @Test
    fun confirmationLoadsAllFourTemplatesAndClearsEveryMaterial() = runTest {
        val coordinator = FakeCoordinator(completeTemplates())
        val matcher = SafetyCommandMatcher(coordinator, FakeEngine("vt_send"))

        assertEquals(
            "vt_send",
            matcher.match(byteArrayOf(9), ConfirmationAction.CONFIRM_SEND),
        )
        assertEquals(SafetyCommandType.entries, coordinator.loadedTypes)
        assertTrue(coordinator.templates.values.all { template ->
            template.candidate.material.all { it == 0.toByte() }
        })
    }

    @Test
    fun missingLocalTemplateNeverFallsBackToKeywordConfirmation() = runTest {
        val matcher = SafetyCommandMatcher(FakeCoordinator(emptyMap()), FakeEngine("vt_any"))

        assertNull(matcher.match(byteArrayOf(9), ConfirmationAction.CANCEL))
    }

    @Test
    fun anotherSafetyCommandNeverConfirmsTheRequestedAction() = runTest {
        val matcher = SafetyCommandMatcher(
            FakeCoordinator(completeTemplates()),
            FakeEngine("vt_cancel"),
        )

        assertNull(matcher.match(byteArrayOf(9), ConfirmationAction.CONFIRM_CALL))
    }

    @Test
    fun `既有四类方言模板分别路由确认取消和同会话重说`() = runTest {
        assertEquals(
            VoiceConfirmationDecision.CONFIRM,
            SafetyCommandMatcher(
                FakeCoordinator(completeTemplates()),
                FakeEngine("vt_call"),
            ).decide(byteArrayOf(9), ConfirmationAction.CONFIRM_CALL),
        )
        assertEquals(
            VoiceConfirmationDecision.REJECT,
            SafetyCommandMatcher(
                FakeCoordinator(completeTemplates()),
                FakeEngine("vt_cancel"),
            ).decide(byteArrayOf(9), ConfirmationAction.CONFIRM_CALL),
        )
        assertEquals(
            VoiceConfirmationDecision.REPEAT,
            SafetyCommandMatcher(
                FakeCoordinator(completeTemplates()),
                FakeEngine("vt_retry"),
            ).decide(byteArrayOf(9), ConfirmationAction.CONFIRM_SEND),
        )
        assertEquals(
            VoiceConfirmationDecision.UNKNOWN,
            SafetyCommandMatcher(
                FakeCoordinator(completeTemplates()),
                FakeEngine("vt_send"),
            ).decide(byteArrayOf(9), ConfirmationAction.CONFIRM_CALL),
        )
    }

    private fun candidate(material: ByteArray) = LocalVoiceTemplateCandidate(
        "zh-Hans-CN-x-wugang",
        "dialect-v1",
        "mfcc-v1",
        "threshold-v1",
        material,
    )

    private class FakeEngine(private val result: String?) : LocalVoiceTemplateEngine {
        override fun enroll(firstWav: ByteArray, secondWav: ByteArray) =
            error("not used")

        override fun classify(
            sampleWav: ByteArray,
            templates: Map<String, LocalVoiceTemplateCandidate>,
        ): String? = result
    }

    private fun completeTemplates(): Map<SafetyCommandType, LocalAvailableVoiceTemplate> = mapOf(
        SafetyCommandType.CONFIRM_SEND to LocalAvailableVoiceTemplate(
            "vt_send", SafetyCommandType.CONFIRM_SEND, candidate(byteArrayOf(1, 2, 3)),
        ),
        SafetyCommandType.CONFIRM_CALL to LocalAvailableVoiceTemplate(
            "vt_call", SafetyCommandType.CONFIRM_CALL, candidate(byteArrayOf(4, 5, 6)),
        ),
        SafetyCommandType.CANCEL to LocalAvailableVoiceTemplate(
            "vt_cancel", SafetyCommandType.CANCEL, candidate(byteArrayOf(7, 8, 9)),
        ),
        SafetyCommandType.REJECT_RETRY to LocalAvailableVoiceTemplate(
            "vt_retry", SafetyCommandType.REJECT_RETRY, candidate(byteArrayOf(10, 11, 12)),
        ),
    )

    private class FakeCoordinator(
        val templates: Map<SafetyCommandType, LocalAvailableVoiceTemplate>,
    ) : LocalVoiceTemplateCoordinator {
        val loadedTypes = mutableListOf<SafetyCommandType>()

        override fun prepare(firstWav: ByteArray, secondWav: ByteArray) = error("not used")

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

        override suspend fun reconcile() =
            LocalTemplateReconciliation(emptySet(), emptySet(), emptySet())

        override suspend fun loadSafetyCommand(
            type: SafetyCommandType,
        ): LocalAvailableVoiceTemplate? {
            loadedTypes += type
            return templates[type]
        }
    }
}
