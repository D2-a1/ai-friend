package com.aifriend.feature.knowledge

import com.aifriend.contract.api.PrivacyApi
import com.aifriend.contract.model.*
import java.io.IOException
import java.time.OffsetDateTime
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeViewModelTest {
    private fun check(block: suspend TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = Fixture()
        try { block(f) } finally { f.vm.leave(); advanceUntilIdle(); Dispatchers.resetMain() }
    }
    @Test fun constructorAndEnterNeverGrantOrCreateAutomatically() = check { f ->
        assertTrue(f.privacy.updates.isEmpty()); f.vm.enter(); runCurrent()
        assertTrue(f.vm.state.value.consentsKnown); assertTrue(f.vm.state.value.grants.isEmpty())
        assertTrue(f.network.api.creates.isEmpty()); assertTrue(f.privacy.updates.isEmpty())
    }
    @Test fun independentGrantsRequireDialogThenExplicitConfirmation() = check { f ->
        f.vm.enter(); runCurrent(); f.vm.confirmConsent(); runCurrent(); assertTrue(f.privacy.updates.isEmpty())
        f.vm.requestConsent(KnowledgeConsentPurpose.MODEL); assertTrue(f.privacy.updates.isEmpty())
        f.vm.dismissConsent(); f.vm.confirmConsent(); runCurrent(); assertTrue(f.privacy.updates.isEmpty())
        f.vm.requestConsent(KnowledgeConsentPurpose.MODEL); f.vm.confirmConsent(); f.vm.confirmConsent(); runCurrent()
        assertEquals(setOf(KnowledgeConsentPurpose.MODEL), f.vm.state.value.grants)
        assertEquals(1, f.privacy.updates.size); assertTrue(f.network.api.creates.isEmpty())
        f.vm.start(AssistantPurpose.CONTACT_GRAPH); runCurrent(); assertTrue(f.network.api.creates.isEmpty())
    }
    @Test fun publicPageSmokeRendersEvidenceThenLeaveClosesAndReentryIsEmpty() = check { f ->
        f.vm.enter(); runCurrent(); f.vm.start(AssistantPurpose.PUBLIC_KNOWLEDGE); runCurrent()
        f.vm.edit("如何使用？"); f.vm.submit(1); runCurrent()
        assertEquals(KnowledgePhase.PRESENTING, f.vm.state.value.conversation.phase)
        assertEquals("说明", f.vm.state.value.conversation.answer!!.citations.single().text)
        f.vm.leave(); assertNull(f.vm.state.value.conversation.answer); assertEquals("", f.vm.state.value.draft)
        runCurrent(); assertEquals(listOf(2L), f.network.api.closes)
        f.vm.enter(); runCurrent(); assertNull(f.vm.state.value.conversation.answer)
        assertEquals(1, f.network.api.creates.size)
    }
    @Test fun graphGrantAllowsOnlyTypedPrivateQuery() = check { f ->
        f.privacy.values = listOf(consent(KnowledgeConsentPurpose.GRAPH))
        f.network.api.ask = {
            Response.success(AssistantQuestionResultResponse("OK", "ok", AssistantQuestionResult(
                f.network.api.asks.last().requestKey, AssistantResultStatus.ANSWERED, AssistantAnswerMode.TEMPLATE,
                AssistantReasonCode.NONE, "找到一位亲友", emptyList(), listOf(KnowledgeGraphCandidate(java.util.UUID(0, 9), 0, listOf("测试亲友"))),
                2, KnowledgeRetrievalMode.NONE), "test"))
        }
        f.vm.enter(); runCurrent(); f.vm.start(AssistantPurpose.CONTACT_GRAPH); runCurrent()
        f.vm.edit("测试亲友"); f.vm.submit(1); runCurrent()
        val request = f.network.api.asks.single()
        assertNull(request.text); assertEquals(KnowledgeGraphQueryType.FIND_CONTACT_BY_ALIAS, request.graphQuery!!.queryType)
        assertEquals("测试亲友", request.graphQuery!!.aliasText)
        assertEquals(KnowledgePhase.PRESENTING, f.vm.state.value.conversation.phase)
        val candidate = f.vm.state.value.conversation.answer!!.candidates.single()
        f.vm.graph(KnowledgeGraphQuery(KnowledgeGraphQueryType.LIST_ALIASES, contactId = candidate.contactId), 1); runCurrent()
        assertEquals(KnowledgeGraphQueryType.LIST_ALIASES, f.network.api.asks.last().graphQuery!!.queryType)
        assertTrue(f.network.api.asks.all { it.text == null })
    }
    @Test fun wrongPolicyIsNotGranted() = check { f ->
        f.privacy.values = listOf(consent(KnowledgeConsentPurpose.GRAPH).copy(policyVersion = "old"))
        f.vm.enter(); runCurrent(); assertTrue(f.vm.state.value.grants.isEmpty())
        f.vm.start(AssistantPurpose.CONTACT_GRAPH); runCurrent(); assertTrue(f.network.api.creates.isEmpty())
    }
    @Test fun uncertainConsentNeverOptimisticallyGrantsAndDoesNotAutoRepeat() = check { f ->
        f.privacy.failUpdate = true
        f.vm.enter(); runCurrent(); f.vm.requestConsent(KnowledgeConsentPurpose.MODEL); f.vm.confirmConsent(); runCurrent()
        assertFalse(f.vm.state.value.consentsKnown); assertTrue(f.vm.state.value.grants.isEmpty())
        assertEquals(KnowledgeFailure.NETWORK_UNCERTAIN, f.vm.state.value.failure)
        f.vm.confirmConsent(); runCurrent(); assertEquals(1, f.privacy.updates.size)
    }
    @Test fun revokeClearsAnswerAndRequiresAuthoritativeRead() = check { f ->
        f.privacy.values = listOf(consent(KnowledgeConsentPurpose.MODEL))
        f.vm.enter(); runCurrent(); f.vm.start(AssistantPurpose.PUBLIC_KNOWLEDGE); runCurrent()
        f.vm.edit("如何使用？"); f.vm.submit(1); runCurrent()
        f.vm.requestConsent(KnowledgeConsentPurpose.MODEL); f.vm.confirmConsent(); runCurrent()
        assertNull(f.vm.state.value.conversation.answer); assertTrue(f.vm.state.value.grants.isEmpty())
        assertEquals(ConsentDecision.REVOKED, f.privacy.updates.single().third.decision)
    }
    @Test fun switchOwnerClearsDialogDraftAndBlocksOldAuthorization() = check { f ->
        f.vm.enter(); runCurrent(); f.vm.edit("不要保留"); f.vm.requestConsent(KnowledgeConsentPurpose.MODEL)
        f.network.auth.loginEpoch++; f.network.auth.session.value = f.network.auth.session.value!!.copy(userId = "another")
        f.vm.confirmConsent(); runCurrent()
        assertNull(f.vm.state.value.pendingConsent); assertEquals("", f.vm.state.value.draft)
        assertEquals(KnowledgeFailure.AUTH_CHANGED, f.vm.state.value.failure); assertTrue(f.privacy.updates.isEmpty())
    }
    @Test fun equalMetadataNewLoginEpochIsDetectedBeforeSubmit() = check { f ->
        f.vm.enter(); runCurrent(); f.vm.start(AssistantPurpose.PUBLIC_KNOWLEDGE); runCurrent(); f.vm.edit("如何使用？")
        f.network.auth.loginEpoch++; f.vm.submit(1); runCurrent()
        assertTrue(f.network.api.asks.isEmpty()); assertNull(f.vm.state.value.conversation.answer)
    }
    @Test fun consent401RetriesSameBodyAndKeyOnce() = check { f ->
        f.privacy.firstUnauthorized = true
        f.vm.enter(); runCurrent(); f.vm.requestConsent(KnowledgeConsentPurpose.MODEL); f.vm.confirmConsent(); runCurrent()
        assertEquals(2, f.privacy.updates.size); assertEquals(1, f.network.auth.refreshes)
        assertEquals(f.privacy.updates[0].second, f.privacy.updates[1].second)
        assertSame(f.privacy.updates[0].third, f.privacy.updates[1].third)
        assertEquals(setOf(KnowledgeConsentPurpose.MODEL), f.vm.state.value.grants)
    }
    @Test fun duplicatedServerConsentFailsClosed() = check { f ->
        f.privacy.values = List(2) { consent(KnowledgeConsentPurpose.MODEL) }
        f.vm.enter(); runCurrent(); assertFalse(f.vm.state.value.consentsKnown)
        assertEquals(KnowledgeFailure.PROTOCOL_INVALID, f.vm.state.value.failure)
    }
    @Test fun refreshClearsOldAnswerAndDoesNotStartNewQuestion() = check { f ->
        f.vm.enter(); runCurrent(); f.vm.start(AssistantPurpose.PUBLIC_KNOWLEDGE); runCurrent()
        f.vm.edit("如何使用？"); f.vm.submit(1); runCurrent(); f.vm.reloadConsents(); runCurrent()
        assertNull(f.vm.state.value.conversation.answer); assertEquals(1, f.network.api.asks.size)
    }
    @Test fun quickReentryKeepsPendingCloseVisibleUntilAcknowledged() = check { f ->
        val original = f.network.api.close
        f.network.api.close = { delay(500); original() }
        f.vm.enter(); runCurrent(); f.vm.start(AssistantPurpose.PUBLIC_KNOWLEDGE); runCurrent()
        f.vm.leave(); f.vm.enter(); runCurrent()
        assertTrue(f.vm.state.value.conversation.cleanupPending)
        advanceTimeBy(501); runCurrent(); assertFalse(f.vm.state.value.conversation.cleanupPending)
        assertNull(f.vm.state.value.conversation.answer)
    }
    @Test fun lateAnswerAfterLeavingCannotReappearOnReentry() = check { f ->
        val original = f.network.api.ask
        f.network.api.ask = { withContext(NonCancellable) { delay(100); original() } }
        f.vm.enter(); runCurrent(); f.vm.start(AssistantPurpose.PUBLIC_KNOWLEDGE); runCurrent()
        f.vm.edit("如何使用？"); f.vm.submit(1); runCurrent(); f.vm.leave(); f.vm.enter(); runCurrent()
        advanceTimeBy(200); runCurrent()
        assertNull(f.vm.state.value.conversation.answer); assertEquals("", f.vm.state.value.draft)
        assertEquals(1, f.network.api.asks.size); assertEquals(1, f.network.api.creates.size)
    }

    @Test fun voiceRequiresExplicitForegroundReadySessionAndPermission() = check { f ->
        f.vm.listen(1); f.vm.enter(); runCurrent(); f.vm.listen(1)
        f.vm.start(AssistantPurpose.PUBLIC_KNOWLEDGE); runCurrent(); f.vm.listen(1)
        assertEquals(0, f.voice.acquisitions)
        f.vm.setVoiceForeground(true); f.voice.unavailableReason = KnowledgeVoiceFailure.MICROPHONE_DENIED
        f.vm.listen(1); runCurrent(); assertEquals(0, f.voice.acquisitions)
        assertEquals(KnowledgeVoiceFailure.MICROPHONE_DENIED, f.vm.state.value.voice.failure)
        f.voice.unavailableReason = null; runCurrent(); assertEquals(0, f.voice.acquisitions)
        f.vm.listen(1); runCurrent(); assertEquals(1, f.voice.captures)
    }

    @Test fun publicVoiceSmokeSubmitsTextOnceSpeaksAnswerAndContinuesOnlyAfterPlayback() = check { f ->
        f.vm.enter(); runCurrent(); f.vm.start(AssistantPurpose.PUBLIC_KNOWLEDGE); runCurrent(); f.vm.setVoiceForeground(true)
        f.voice.transcripts += "如何使用？"; f.vm.listen(1); f.vm.listen(1); runCurrent()
        assertEquals(1, f.network.api.asks.size)
        assertEquals("如何使用？", f.network.api.asks.single().text)
        assertNull(f.network.api.asks.single().graphQuery)
        assertTrue(f.voice.spoken.contains(f.vm.state.value.conversation.answer!!.text))
        assertEquals(KnowledgeVoicePhase.LISTENING, f.vm.state.value.voice.phase)
        assertEquals(2, f.voice.captures)
        assertTrue(f.voice.audio.all { a -> a.wavBytes.all { it == 0.toByte() } })
    }

    @Test fun graphVoiceSmokeKeepsPrivateTextLocalAndOrdinalsUseCurrentCandidates() = check { f ->
        f.privacy.values = listOf(consent(KnowledgeConsentPurpose.GRAPH))
        val id = java.util.UUID(0, 9)
        f.network.api.ask = {
            Response.success(AssistantQuestionResultResponse("OK", "ok", AssistantQuestionResult(
                f.network.api.asks.last().requestKey, AssistantResultStatus.ANSWERED, AssistantAnswerMode.TEMPLATE,
                AssistantReasonCode.NONE, "找到亲友", emptyList(), listOf(KnowledgeGraphCandidate(id, 0, listOf("测试亲友"))),
                f.network.api.asks.size.toLong() + 1, KnowledgeRetrievalMode.NONE), "test"))
        }
        f.vm.enter(); runCurrent(); f.vm.start(AssistantPurpose.CONTACT_GRAPH); runCurrent(); f.vm.setVoiceForeground(true)
        f.voice.transcripts.addAll(listOf("列出亲友", "第一位")); f.vm.listen(1); runCurrent()
        assertEquals(2, f.network.api.asks.size); assertTrue(f.network.api.asks.all { it.text == null })
        assertEquals(KnowledgeGraphQueryType.LIST_CONTACTS, f.network.api.asks[0].graphQuery!!.queryType)
        assertEquals(KnowledgeGraphQueryType.LIST_ALIASES, f.network.api.asks[1].graphQuery!!.queryType)
        assertEquals(id, f.network.api.asks[1].graphQuery!!.contactId)
        assertTrue(f.voice.spoken.any { "第1位" in it && "测试亲友" in it })
    }

    @Test fun graphContactCommandOnlyGetsLocalHelpAndNeverFallsBackToPublicQuestion() = check { f ->
        f.privacy.values = listOf(consent(KnowledgeConsentPurpose.GRAPH))
        f.vm.enter(); runCurrent(); f.vm.start(AssistantPurpose.CONTACT_GRAPH); runCurrent(); f.vm.setVoiceForeground(true)
        f.voice.transcripts += "给亲友打电话"; f.vm.listen(1); runCurrent()
        assertTrue(f.network.api.asks.isEmpty()); assertTrue(f.voice.spoken.contains(KnowledgeGraphSpeechParser.HELP))
    }

    @Test fun backgroundDuringPromptPreventsMicrophoneAndResumeDoesNotAutoRestart() = check { f ->
        f.vm.enter(); runCurrent(); f.vm.start(AssistantPurpose.PUBLIC_KNOWLEDGE); runCurrent(); f.vm.setVoiceForeground(true)
        f.voice.speechGate = CompletableDeferred(); f.vm.listen(1); runCurrent()
        f.vm.setVoiceForeground(false); runCurrent(); f.voice.speechGate!!.complete(Unit); runCurrent()
        assertEquals(0, f.voice.captures); assertEquals(1, f.voice.releases)
        f.vm.setVoiceForeground(true); runCurrent(); assertEquals(1, f.voice.acquisitions)
    }

    @Test fun communicationModeOrPermissionLossStopsCurrentCaptureWithoutSubmitting() = check { f ->
        f.vm.enter(); runCurrent(); f.vm.start(AssistantPurpose.PUBLIC_KNOWLEDGE); runCurrent(); f.vm.setVoiceForeground(true)
        f.vm.listen(1); runCurrent(); f.voice.unavailableReason = KnowledgeVoiceFailure.AUDIO_BUSY
        advanceTimeBy(251); runCurrent()
        assertEquals(1, f.voice.cancelled); assertTrue(f.network.api.asks.isEmpty())
        assertEquals(KnowledgeVoicePhase.CLOSED, f.vm.state.value.voice.phase)
        f.voice.unavailableReason = null; advanceTimeBy(251); runCurrent(); assertEquals(1, f.voice.acquisitions)
    }

    @Test fun unknownVoiceSubmissionNeverRepostsOrClaimsNoSpeech() = check { f ->
        f.network.api.ask = { throw IOException("synthetic") }
        f.network.api.read = { throw IOException("synthetic lookup unavailable") }
        f.vm.enter(); runCurrent(); f.vm.start(AssistantPurpose.PUBLIC_KNOWLEDGE); runCurrent(); f.vm.setVoiceForeground(true)
        f.voice.transcripts += "如何使用？"; f.vm.listen(1); runCurrent()
        assertTrue(f.vm.state.value.conversation.canQueryOriginal)
        assertEquals(KnowledgeVoicePhase.AWAITING_CONTINUE, f.vm.state.value.voice.phase)
        f.vm.listen(1); runCurrent(); assertEquals(1, f.network.api.asks.size); assertEquals(1, f.voice.captures)
        assertEquals(listOf(f.network.api.asks.single().requestKey), f.network.api.reads)
    }

    @Test fun interruptedPostWithSuccessfulOriginalLookupSpeaksRecoveredResultWithoutReposting() = check { f ->
        f.network.api.ask = { throw IOException("synthetic") }
        f.vm.enter(); runCurrent(); f.vm.start(AssistantPurpose.PUBLIC_KNOWLEDGE); runCurrent(); f.vm.setVoiceForeground(true)
        f.voice.transcripts += "如何使用？"; f.vm.listen(1); runCurrent()
        assertEquals(1, f.network.api.asks.size)
        assertEquals(listOf(f.network.api.asks.single().requestKey), f.network.api.reads)
        assertEquals(KnowledgeVoicePhase.LISTENING, f.vm.state.value.voice.phase)
        assertTrue(f.voice.spoken.contains("说明"))
    }

    @Test fun startingVoiceFromPreviousTextAnswerCannotSpeakOldAnswerAsNewResult() = check { f ->
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        f.vm.enter(); runCurrent(); f.vm.start(AssistantPurpose.PUBLIC_KNOWLEDGE); runCurrent()
        f.vm.edit("如何使用？"); f.vm.submit(1); runCurrent()
        val original = f.network.api.ask
        f.network.api.ask = {
            delay(100)
            val response = original().body()!!
            Response.success(response.copy(data = response.data.copy(version = 3, text = "新的说明")))
        }
        f.vm.setVoiceForeground(true); f.voice.transcripts += "另外怎么使用？"; f.vm.listen(1); runCurrent()
        assertFalse(f.voice.spoken.contains("说明")); assertEquals(2, f.network.api.asks.size)
        advanceTimeBy(101); runCurrent()
        assertTrue(f.voice.spoken.contains("新的说明")); assertFalse(f.voice.spoken.contains("说明"))
    }

    @Test fun accountChangeDuringPromptCannotUploadLateTranscript() = check { f ->
        f.vm.enter(); runCurrent(); f.vm.start(AssistantPurpose.PUBLIC_KNOWLEDGE); runCurrent(); f.vm.setVoiceForeground(true)
        f.voice.speechGate = CompletableDeferred(); f.voice.transcripts += "如何使用？"
        f.vm.listen(1); runCurrent(); f.network.auth.loginEpoch++
        advanceTimeBy(251); runCurrent(); f.voice.speechGate!!.complete(Unit); runCurrent()
        assertEquals(0, f.voice.captures); assertTrue(f.network.api.asks.isEmpty()); assertNull(f.vm.state.value.conversation.answer)
    }

    @Test fun consentDialogStopsAudioBeforeAuthorizationCanChange() = check { f ->
        f.vm.enter(); runCurrent(); f.vm.start(AssistantPurpose.PUBLIC_KNOWLEDGE); runCurrent(); f.vm.setVoiceForeground(true)
        f.vm.listen(1); runCurrent(); f.vm.requestConsent(KnowledgeConsentPurpose.MODEL); runCurrent()
        assertEquals(1, f.voice.cancelled); assertTrue(f.privacy.updates.isEmpty())
        f.vm.dismissConsent(); runCurrent(); assertEquals(1, f.voice.acquisitions)
    }

    @Test fun failedAudioCleanupBlocksNewVoiceButAllowsText() = check { f ->
        f.vm.enter(); runCurrent(); f.vm.start(AssistantPurpose.PUBLIC_KNOWLEDGE); runCurrent(); f.vm.setVoiceForeground(true)
        f.voice.cleanupFails = true; f.vm.listen(1); runCurrent(); f.vm.stopListening(); runCurrent()
        assertTrue(f.vm.state.value.audioCleanupPending)
        assertEquals(1, f.voice.retentions); assertEquals(0, f.voice.releases)
        f.vm.listen(1); runCurrent(); assertEquals(1, f.voice.acquisitions)
        f.vm.edit("如何使用？"); f.vm.submit(1); runCurrent(); assertEquals(1, f.network.api.asks.size)
    }

    private class Fixture {
        val network = DefaultKnowledgeRepositoryTest.Fixture()
        val privacy = PrivacyStub()
        val voice = KnowledgeVoiceTestFactory()
        val vm = KnowledgeViewModel(network.repo, KnowledgeConsentAccess(privacy.api, network.auth), network.auth, voice)
    }
    /** 只实现这两个接口，触及任何其他隐私写入立即失败。无网络、数据库或真实账号。 */
    private class PrivacyStub {
        var values = emptyList<Consent>()
        var failUpdate = false; var firstUnauthorized = false
        val updates = mutableListOf<Triple<ConsentType, String, UpdateConsentRequest>>()
        val api = object : PrivacyApi {
            override suspend fun listMyConsents() = Response.success(ConsentListResponse(ConsentListResponse.Code.OK, "ok", values, "test"))
            override suspend fun updateMyConsent(type: ConsentType, idempotencyKey: String, updateConsentRequest: UpdateConsentRequest): Response<ConsentResponse> {
                    val key = idempotencyKey; val request = updateConsentRequest
                    updates += Triple(type, key, request)
                    if (failUpdate) throw IOException("synthetic")
                    return if (firstUnauthorized && updates.size == 1) Response.error<ConsentResponse>(401, "{}".toResponseBody())
                    else {
                        val value = Consent(type, request.decision, request.policyVersion, request.confirmedAt)
                        values = values.filterNot { it.type == type } + value
                        Response.success(ConsentResponse(ConsentResponse.Code.OK, "ok", value, "test"))
                    }
            }
            override suspend fun clearMyTaskHistory(idempotencyKey: String, confirmedTaskHistoryDeletionRequest: ConfirmedTaskHistoryDeletionRequest): Response<TaskHistoryDeletionResponse> = error("Unused")
            override suspend fun closeMyAccount(idempotencyKey: String, confirmedAccountClosureRequest: ConfirmedAccountClosureRequest): Response<AccountClosureResponse> = error("Unused")
            override suspend fun deleteMyPersonalMemory(idempotencyKey: String, deletePersonalMemoryRequest: DeletePersonalMemoryRequest): Response<PersonalMemoryResponse> = error("Unused")
            override suspend fun getMyPersonalMemory(): Response<PersonalMemoryResponse> = error("Unused")
            override suspend fun getMyTaskHistoryDeletion(): Response<TaskHistoryDeletionResponse> = error("Unused")
            override suspend fun updateMyPersonalMemory(idempotencyKey: String, updatePersonalMemoryRequest: UpdatePersonalMemoryRequest): Response<PersonalMemoryResponse> = error("Unused")
        }
    }
    companion object {
        private fun consent(p: KnowledgeConsentPurpose) = Consent(p.type, ConsentDecision.GRANTED, p.policy, OffsetDateTime.parse("2026-09-11T01:00:00Z"))
    }
}
