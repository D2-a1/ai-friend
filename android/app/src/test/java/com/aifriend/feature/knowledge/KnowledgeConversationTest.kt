package com.aifriend.feature.knowledge

import com.aifriend.contract.model.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

/** 真状态机→真仓储→生成DTO；HTTP/认证为替身，虚拟时间验证有限轮询和关闭。 */
@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeConversationTest {
    @Test fun publicQuestionSmokeShowsEvidenceThenCancelsAndClosesActualVersion() = runTest {
        val f = Fixture(this); f.open(); f.conversation.submit(question()); runCurrent()
        assertEquals(KnowledgePhase.PRESENTING, f.state.phase)
        assertEquals("说明", f.state.answer!!.citations.single().text)
        f.conversation.cancel(); assertNull(f.state.answer); runCurrent()
        assertEquals(KnowledgePhase.CANCELLED, f.state.phase)
        assertEquals(listOf(2L), f.network.api.closes); assertFalse(f.state.cleanupPending)
    }
    @Test fun secondTapWhileSubmittingDoesNotProduceSecondPost() = runTest {
        val f = Fixture(this); f.open()
        f.network.api.ask = { delay(100); answer(f.network.api.asks.last().requestKey) }
        f.conversation.submit(question()); f.conversation.submit(question()); runCurrent()
        assertEquals(1, f.network.api.asks.size)
        advanceTimeBy(101); runCurrent(); assertEquals(KnowledgePhase.PRESENTING, f.state.phase)
    }
    @Test fun processingPollsAtMostEightTimesThenOffersOriginalRequestQuery() = runTest {
        val f = Fixture(this); f.open()
        f.network.api.ask = { processing(f.network.api.asks.last().requestKey) }
        f.network.api.read = { processing(f.network.api.reads.last()) }
        f.conversation.submit(question()); runCurrent(); advanceTimeBy(9000); runCurrent()
        assertEquals(8, f.network.api.reads.size); assertEquals(1, f.network.api.asks.size)
        assertEquals(KnowledgePhase.ERROR, f.state.phase); assertTrue(f.state.canQueryOriginal); assertNull(f.state.answer)
        f.conversation.submit(question()); runCurrent(); assertEquals(1, f.network.api.asks.size)
    }
    @Test fun unknownSubmissionRecoversWithGetOnly() = runTest {
        val f = Fixture(this); f.open()
        f.network.api.ask = { throw IOException() }; f.network.api.read = { throw IOException() }
        f.conversation.submit(question()); runCurrent()
        assertTrue(f.state.canQueryOriginal)
        val key = f.network.api.asks.single().requestKey
        f.network.api.read = { answer(f.network.api.reads.last()) }
        f.conversation.queryOriginal(); runCurrent()
        assertEquals(KnowledgePhase.PRESENTING, f.state.phase)
        assertEquals(1, f.network.api.asks.size); assertEquals(listOf(key, key), f.network.api.reads)
    }
    @Test fun cancelledNonCooperativeReplyCannotRestoreAnswer() = runTest {
        val f = Fixture(this); f.open()
        f.network.api.ask = { withContext(NonCancellable) { delay(100); answer(f.network.api.asks.last().requestKey) } }
        f.conversation.submit(question()); runCurrent(); f.conversation.cancel(); runCurrent()
        advanceTimeBy(200); runCurrent()
        assertEquals(KnowledgePhase.CANCELLED, f.state.phase); assertNull(f.state.answer)
    }
    @Test fun ownerChangeClearsDisplayedPrivateOrPublicDataAndDoesNotCloseAsNewOwner() = runTest {
        val f = Fixture(this); f.open(); f.conversation.submit(question()); runCurrent()
        f.network.auth.loginEpoch++
        f.network.auth.session.value = f.network.auth.session.value!!.copy(userId = "other")
        runCurrent()
        assertEquals(KnowledgePhase.CANCELLED, f.state.phase); assertNull(f.state.answer)
        assertEquals(KnowledgeFailure.AUTH_CHANGED, f.state.failure)
        assertTrue(f.network.api.closes.isEmpty()); assertTrue(f.state.cleanupPending)
    }
    @Test fun changingPurposeClosesOldContextAndCreatesDifferentPurpose() = runTest {
        val f = Fixture(this); f.open(); f.conversation.submit(question()); runCurrent()
        f.conversation.start(AssistantPurpose.CONTACT_GRAPH); assertNull(f.state.answer); runCurrent()
        assertEquals(AssistantPurpose.CONTACT_GRAPH, f.state.purpose); assertEquals(KnowledgePhase.READY, f.state.phase)
        assertEquals(2, f.network.api.creates.size); assertEquals(listOf(2L), f.network.api.closes)
        f.network.api.ask = { graph(f.network.api.asks.last().requestKey) }
        f.conversation.submit(KnowledgeQuestion.Graph(KnowledgeGraphQuery(KnowledgeGraphQueryType.LIST_CONTACTS), "zh-CN", 1)); runCurrent()
        assertEquals(AssistantAnswerMode.TEMPLATE, f.state.answer!!.answerMode)
        assertNull(f.network.api.asks.last().text)
    }
    @Test fun shutdownRemovesAnswerAndNeverAllowsRestart() = runTest {
        val f = Fixture(this); f.open(); f.conversation.submit(question()); runCurrent()
        f.conversation.shutdown()
        assertEquals(KnowledgePhase.CLOSED, f.state.phase); assertNull(f.state.answer)
        f.conversation.start(AssistantPurpose.PUBLIC_KNOWLEDGE); runCurrent()
        assertEquals(1, f.network.api.creates.size); assertEquals(listOf(2L), f.network.api.closes)
    }
    @Test fun closeConflictIsVisibleAsPendingCleanupNotSuccess() = runTest {
        val f = Fixture(this); f.open(); f.network.api.close = { Response.error(409, "{}".toResponseBody()) }
        f.conversation.cancel(); runCurrent()
        assertTrue(f.state.cleanupPending); assertEquals(1, f.network.api.closes.size)
    }
    @Test fun completedCancelClosesKnownVersionWithoutReadingAnswerAgain() = runTest {
        val f = Fixture(this); f.open(); f.conversation.submit(question()); runCurrent()
        f.network.api.read = { error("Completed cleanup must not reread answer") }
        f.conversation.cancel(); runCurrent()
        assertTrue(f.network.api.reads.isEmpty()); assertEquals(listOf(2L), f.network.api.closes)
        assertFalse(f.state.cleanupPending); assertNull(f.state.answer)
    }
    @Test fun processingCancelStillReadsOnlyOriginalKeyForCurrentVersion() = runTest {
        val f = Fixture(this); f.open()
        f.network.api.ask = { processing(f.network.api.asks.last().requestKey) }
        f.conversation.submit(question()); runCurrent()
        val original = f.network.api.asks.single().requestKey
        f.network.api.read = { answer(f.network.api.reads.last()) }
        f.conversation.cancel(); runCurrent()
        assertEquals(listOf(original), f.network.api.reads); assertEquals(listOf(2L), f.network.api.closes)
        assertEquals(1, f.network.api.asks.size); assertFalse(f.state.cleanupPending); assertNull(f.state.answer)
    }
    @Test fun completedCloseConflictDoesNotRefreshAndBlindlyRetry() = runTest {
        val f = Fixture(this); f.open(); f.conversation.submit(question()); runCurrent()
        f.network.api.close = { Response.error(409, "{}".toResponseBody()) }
        f.conversation.cancel(); runCurrent()
        assertTrue(f.state.cleanupPending); assertEquals(listOf(2L), f.network.api.closes)
        assertTrue(f.network.api.reads.isEmpty()); assertNull(f.state.answer)
    }
    @Test fun hangingCloseIsBoundedToThreeSeconds() = runTest {
        val f = Fixture(this); f.open()
        f.network.api.close = { delay(10000); Response.error(503, "{}".toResponseBody()) }
        val closed = async { f.conversation.shutdown() }; runCurrent(); advanceTimeBy(3001); runCurrent()
        assertTrue(closed.isCompleted); assertTrue(f.state.cleanupPending)
    }
    @Test fun interruptedCleanupRemainsPendingWhenPageDisposes() = runTest {
        val f = Fixture(this); f.open()
        f.network.api.close = { delay(10000); Response.error(503, "{}".toResponseBody()) }
        f.conversation.cancel(); runCurrent(); f.conversation.shutdown()
        assertTrue(f.state.cleanupPending); assertNull(f.state.answer)
    }
    @Test fun uncertainCreateRetryKeepsSameCreateKey() = runTest {
        val f = Fixture(this)
        val original = f.network.api.create
        f.network.api.create = { if (f.network.api.creates.size == 1) throw IOException() else original() }
        f.open(); assertEquals(KnowledgeFailure.NETWORK_UNCERTAIN, f.state.failure)
        f.conversation.start(AssistantPurpose.PUBLIC_KNOWLEDGE); runCurrent()
        assertEquals(KnowledgePhase.READY, f.state.phase)
        assertEquals(f.network.api.creates[0].clientRequestId, f.network.api.creates[1].clientRequestId)
    }
    @Test fun invalidNewQuestionClearsOldDisplayedAnswerWithoutPosting() = runTest {
        val f = Fixture(this); f.open(); f.conversation.submit(question()); runCurrent()
        f.conversation.submit(KnowledgeQuestion.Text("", "zh-CN", 1)); runCurrent()
        assertEquals(KnowledgeFailure.INPUT_INVALID, f.state.failure); assertNull(f.state.answer)
        assertEquals(1, f.network.api.asks.size)
    }
    @Test fun explicitCommitUncertaintyKeepsOriginalQueryAction() = runTest {
        val f = Fixture(this); f.open()
        f.network.api.ask = { Response.error(503, """{"data":{"reasonCode":"COMMIT_UNCERTAIN"}}""".toResponseBody()) }
        f.conversation.submit(question()); runCurrent()
        assertTrue(f.state.canQueryOriginal); assertNull(f.state.answer)
        f.conversation.queryOriginal(); runCurrent()
        assertEquals(KnowledgePhase.PRESENTING, f.state.phase); assertEquals(1, f.network.api.asks.size)
    }
    private class Fixture(private val scope: TestScope) {
        val network = DefaultKnowledgeRepositoryTest.Fixture()
        private var keys = 0
        val conversation = KnowledgeConversation(network.repo, network.auth, scope.backgroundScope) { "question-key-${++keys}-000001" }
        val state get() = conversation.state.value
        fun open() { conversation.start(AssistantPurpose.PUBLIC_KNOWLEDGE); scope.runCurrent() }
    }
    companion object {
        private fun question() = KnowledgeQuestion.Text("怎么使用？", "zh-CN", 1)
        private fun answer(key: String): Response<AssistantQuestionResultResponse> {
            val id = java.util.UUID(0, 1)
            val citation = KnowledgeCitation("e1", id, 1, id, "说明", "说明", 0, 2)
            return Response.success(AssistantQuestionResultResponse("OK", "success", AssistantQuestionResult(key,
                AssistantResultStatus.EVIDENCE_ONLY, AssistantAnswerMode.EXTRACTIVE, AssistantReasonCode.NONE, "说明",
                listOf(citation), emptyList(), 2, KnowledgeRetrievalMode.KEYWORD_ONLY), "test"))
        }
        private fun processing(key: String) = Response.success(answer(key).body()!!.let { it.copy(data = it.data.copy(
            status = AssistantResultStatus.PROCESSING, answerMode = AssistantAnswerMode.NONE, citations = emptyList(),
            text = "", version = 1, retrievalMode = KnowledgeRetrievalMode.NONE)) })
        private fun graph(key: String) = Response.success(answer(key).body()!!.let { it.copy(data = it.data.copy(
            status = AssistantResultStatus.ANSWERED, answerMode = AssistantAnswerMode.TEMPLATE, citations = emptyList(),
            candidates = listOf(KnowledgeGraphCandidate(java.util.UUID(0, 2), 0, listOf("测试亲友"))), retrievalMode = KnowledgeRetrievalMode.NONE)) })
    }
}
