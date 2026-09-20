package com.aifriend.feature.knowledge

import com.aifriend.contract.api.AssistantKnowledgeApi
import com.aifriend.contract.model.*
import com.aifriend.core.network.NetworkModule
import com.aifriend.feature.auth.*
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

/** 实际仓储/DTO与模拟HTTP/登录端口，无网络、模型、数据库或消息发送。 */
class DefaultKnowledgeRepositoryTest {
    @Test fun zeroVersionCreateAskReadAndCloseUseActualVersionAndOriginalKey() = runTest {
        val f = Fixture(); val session = f.create()
        assertEquals(0L, session.metadata.version)
        val answer = f.repo.ask(session, question(), KEY)
        assertEquals(2L, answer.session.metadata.version)
        assertEquals("e1", answer.result.citations.single().evidenceId)
        val reloaded = f.repo.read(answer.session, KEY)
        f.repo.close(reloaded.session)
        assertEquals(listOf(0L), f.api.asks.map { it.expectedVersion })
        assertEquals(listOf(KEY), f.api.reads)
        assertEquals(listOf(2L), f.api.closes)
    }
    @Test fun unauthorizedRetriesExactlySameCreateBodyOnce() = runTest {
        val f = Fixture(); f.api.create = { if (f.api.creates.size == 1) error(401) else sessionResponse() }
        f.create()
        assertEquals(1, f.auth.refreshes); assertSame(f.api.creates[0], f.api.creates[1])
    }
    @Test fun unauthorizedAskKeepsBodyKeyAndVersion() = runTest {
        val f = Fixture(); val session = f.create()
        f.api.ask = { if (f.api.asks.size == 1) error(401) else resultResponse(result()) }
        val answer = f.repo.ask(session, question(), KEY)
        assertEquals(1, f.auth.refreshes); assertSame(f.api.asks[0], f.api.asks[1])
        assertTrue(f.repo.isCurrent(answer.session))
    }
    @Test fun second401DoesNotLoop() = runTest {
        val f = Fixture(); val s = f.create(); f.api.ask = { error(401) }
        fails(KnowledgeFailure.AUTH_REQUIRED) { f.repo.ask(s, question(), KEY) }
        assertEquals(2, f.api.asks.size); assertEquals(1, f.auth.refreshes); assertTrue(f.api.reads.isEmpty())
    }
    @Test fun timeoutOnlyReadsOriginalRequestNeverPostsAgain() = runTest {
        val f = Fixture(); val s = f.create(); f.api.ask = { throw SocketTimeoutException("private") }
        val result = f.repo.ask(s, question(), KEY)
        assertEquals(KEY, result.result.requestKey); assertEquals(1, f.api.asks.size); assertEquals(listOf(KEY), f.api.reads)
    }
    @Test fun recoveryGetAndPostShareOneRefreshBudget() = runTest {
        val f = Fixture(); val s = f.create()
        f.api.ask = { if (f.api.asks.size == 1) error(401) else throw IOException() }
        f.api.read = { error(401) }
        fails(KnowledgeFailure.AUTH_REQUIRED) { f.repo.ask(s, question(), KEY) }
        assertEquals(1, f.auth.refreshes); assertEquals(2, f.api.asks.size); assertEquals(1, f.api.reads.size)
    }
    @Test fun missingUnknownSubmissionIsNotResubmitted() = runTest {
        val f = Fixture(); val s = f.create(); f.api.ask = { throw IOException() }; f.api.read = { error(404) }
        fails(KnowledgeFailure.NOT_FOUND) { f.repo.ask(s, question(), KEY) }
        assertEquals(1, f.api.asks.size); assertEquals(1, f.api.reads.size)
    }
    @Test fun networkFailureOnCreateDoesNotGenerateAnotherKeyOrRetry() = runTest {
        val f = Fixture(); f.api.create = { throw IOException() }
        fails(KnowledgeFailure.NETWORK_UNCERTAIN) { f.create() }
        assertEquals(1, f.api.creates.size)
    }
    @Test fun processingIsNotClaimedAsAnsweredOrPolledForever() = runTest {
        val f = Fixture(); val s = f.create()
        f.api.ask = { resultResponse(result().copy(status = AssistantResultStatus.PROCESSING, answerMode = AssistantAnswerMode.NONE,
            citations = emptyList(), text = "", version = 1, retrievalMode = KnowledgeRetrievalMode.NONE)) }
        val answer = f.repo.ask(s, question(), KEY)
        assertEquals(AssistantResultStatus.PROCESSING, answer.result.status)
        assertTrue(f.api.reads.isEmpty())
    }
    @Test fun cancellationNeverTriggersRecoveryGet() = runTest {
        val f = Fixture(); val s = f.create(); f.api.ask = { throw CancellationException() }
        try { f.repo.ask(s, question(), KEY); fail() } catch (_: CancellationException) { }
        assertTrue(f.api.reads.isEmpty())
    }
    @Test fun swallowedCancellationStillCannotReturnLateAnswer() = runTest {
        val f = Fixture(); val s = f.create()
        f.api.ask = { currentCoroutineContext().cancel(); resultResponse(result()) }
        var returned = false
        val job = launch { f.repo.ask(s, question(), KEY); returned = true }
        job.join(); assertFalse(returned); assertTrue(job.isCancelled)
    }
    @Test fun changedOwnerDuringReplyRejectsAnswerAndRefresh() = runTest {
        val f = Fixture(); val s = f.create()
        f.api.ask = { f.auth.session.value = identity("other"); resultResponse(result()) }
        fails(KnowledgeFailure.AUTH_CHANGED) { f.repo.ask(s, question(), KEY) }
        assertEquals(0, f.auth.refreshes); assertFalse(f.repo.isCurrent(s))
    }
    @Test fun logoutOrSameOwnerNewLoginInvalidatesOldHandleBeforeNetwork() = runTest {
        val f = Fixture(); val s = f.create(); f.auth.session.value = null
        fails(KnowledgeFailure.AUTH_CHANGED) { f.repo.read(s, KEY) }
        f.auth.session.value = identity("owner").copy(accessTokenExpiresAt = Instant.parse("2026-09-11T01:01:00Z"))
        fails(KnowledgeFailure.AUTH_CHANGED) { f.repo.read(s, KEY) }
        assertTrue(f.api.reads.isEmpty())
    }
    @Test fun refreshReturningDifferentOwnerNeverReplaysBody() = runTest {
        val f = Fixture(); val s = f.create(); f.api.ask = { error(401) }; f.auth.refreshedOwner = "other"
        fails(KnowledgeFailure.AUTH_CHANGED) { f.repo.ask(s, question(), KEY) }
        assertEquals(1, f.api.asks.size)
    }
    @Test fun equalVisibleSessionAfterLogoutLoginStillInvalidatesOldEpoch() = runTest {
        val f = Fixture(); val s = f.create(); f.auth.loginEpoch++
        fails(KnowledgeFailure.AUTH_CHANGED) { f.repo.read(s, KEY) }
        assertTrue(f.api.reads.isEmpty()); assertFalse(f.repo.isCurrent(s))
    }
    @Test fun loginBoundaryDuringRefreshDoesNotReplayEvenForSameOwner() = runTest {
        val f = Fixture(); val s = f.create(); f.api.ask = { error(401) }; f.auth.changeEpochOnRefresh = true
        fails(KnowledgeFailure.AUTH_CHANGED) { f.repo.ask(s, question(), KEY) }
        assertEquals(1, f.api.asks.size)
    }
    @Test fun refreshWithIdenticalUiMetadataIsAcceptedInSameEpoch() = runTest {
        val f = Fixture(); f.auth.identicalRefresh = true; val s = f.create()
        f.api.ask = { if (f.api.asks.size == 1) error(401) else resultResponse(result()) }
        assertEquals(2L, f.repo.ask(s, question(), KEY).session.metadata.version)
        assertEquals(1, f.auth.refreshes)
    }
    @Test fun responseKeyVersionAndPurposeAreValidated() = runTest {
        for (bad in listOf(result().copy(requestKey = "different-key-000001"), result().copy(version = -1),
            result().copy(candidates = listOf(candidate())), result().copy(answerMode = AssistantAnswerMode.TEMPLATE))) {
            val f = Fixture(); val s = f.create(); f.api.ask = { resultResponse(bad) }
            fails(KnowledgeFailure.PROTOCOL_INVALID) { f.repo.ask(s, question(), KEY) }
        }
    }
    @Test fun validGraphQueryHasNoPublicTextAndAcceptsZeroContactVersion() = runTest {
        val f = Fixture(); f.api.create = { sessionResponse(AssistantPurpose.CONTACT_GRAPH) }
        val s = f.repo.create(AssistantPurpose.CONTACT_GRAPH, KEY)
        f.api.ask = { resultResponse(result().copy(status = AssistantResultStatus.ANSWERED, answerMode = AssistantAnswerMode.TEMPLATE,
            citations = emptyList(), candidates = listOf(candidate()), retrievalMode = KnowledgeRetrievalMode.NONE)) }
        val answer = f.repo.ask(s, KnowledgeQuestion.Graph(KnowledgeGraphQuery(KnowledgeGraphQueryType.LIST_CONTACTS), "zh-CN", 1), KEY)
        assertNull(f.api.asks.single().text); assertNotNull(f.api.asks.single().graphQuery)
        assertEquals(0L, answer.result.candidates.single().contactVersion)
    }
    @Test fun graphNeverAcceptsTextGeneratedModeOrPublicCitations() = runTest {
        val f = Fixture(); f.api.create = { sessionResponse(AssistantPurpose.CONTACT_GRAPH) }
        val s = f.repo.create(AssistantPurpose.CONTACT_GRAPH, KEY)
        fails(KnowledgeFailure.INPUT_INVALID) { f.repo.ask(s, question(), KEY) }
        fails(KnowledgeFailure.PROTOCOL_INVALID) {
            f.repo.ask(s, KnowledgeQuestion.Graph(KnowledgeGraphQuery(KnowledgeGraphQueryType.LIST_CONTACTS), "zh-CN", 1), KEY)
        }
    }
    @Test fun malformedGraphUnionNeverLeavesDevice() = runTest {
        val f = Fixture(); f.api.create = { sessionResponse(AssistantPurpose.CONTACT_GRAPH) }
        val s = f.repo.create(AssistantPurpose.CONTACT_GRAPH, KEY)
        for (q in listOf(KnowledgeGraphQuery(KnowledgeGraphQueryType.LIST_CONTACTS, aliasText = "私有"),
            KnowledgeGraphQuery(KnowledgeGraphQueryType.LIST_ALIASES),
            KnowledgeGraphQuery(KnowledgeGraphQueryType.FIND_CONTACT_BY_ALIAS, ID, "亲友"))) {
            fails(KnowledgeFailure.INPUT_INVALID) { f.repo.ask(s, KnowledgeQuestion.Graph(q, "zh-CN", 1), KEY) }
        }
        assertTrue(f.api.asks.isEmpty())
    }
    @Test fun unicodeCodePointLimitAndInvalidSurrogates() = runTest {
        val f = Fixture(); val s = f.create()
        f.repo.ask(s, KnowledgeQuestion.Text("😀".repeat(500), "zh-CN", 1), KEY)
        for (text in listOf("😀".repeat(501), "\uD800", "", "   ", "a\u0000", "a\u007F", "a\u0085"))
            fails(KnowledgeFailure.INPUT_INVALID) { f.repo.ask(s, KnowledgeQuestion.Text(text, "zh-CN", 1), KEY) }
        assertEquals(1, f.api.asks.size)
    }
    @Test fun badCitationIsNeverShownAsEvidence() = runTest {
        for (bad in listOf(citation().copy(sourceEnd = 5), citation().copy(evidenceId = "E1"), citation().copy(documentVersion = 0))) {
            val f = Fixture(); val s = f.create(); f.api.ask = { resultResponse(result().copy(citations = listOf(bad))) }
            fails(KnowledgeFailure.PROTOCOL_INVALID) { f.repo.ask(s, question(), KEY) }
        }
    }
    @Test fun fixedErrorsDoNotEchoBodyAndDoNotRetry403409429503() = runTest {
        for ((code, kind) in mapOf(403 to KnowledgeFailure.ACCESS_DENIED, 409 to KnowledgeFailure.CONFLICT,
            413 to KnowledgeFailure.INPUT_INVALID, 422 to KnowledgeFailure.INPUT_INVALID,
            429 to KnowledgeFailure.RATE_LIMITED, 503 to KnowledgeFailure.UNAVAILABLE)) {
            val f = Fixture(); val s = f.create()
            f.api.ask = { Response.error(code, """{"message":"secret-input","data":{"reasonCode":"ACCESS_REVOKED"}}""".toResponseBody()) }
            val failure = fails(kind) { f.repo.ask(s, question(), KEY) }
            assertEquals(AssistantReasonCode.ACCESS_REVOKED, failure.reason); assertFalse(failure.toString().contains("secret-input"))
            assertEquals(1, f.api.asks.size); assertEquals(0, f.auth.refreshes)
        }
    }
    @Test fun twoHundredWithWrongCodeOrMissingBodyIsProtocolFailure() = runTest {
        val f = Fixture(); val s = f.create(); f.api.ask = { Response.success(resultResponse(result()).body()!!.copy(code = "ERROR")) }
        fails(KnowledgeFailure.PROTOCOL_INVALID) { f.repo.ask(s, question(), KEY) }
        f.api.ask = { Response.success(null) }
        fails(KnowledgeFailure.PROTOCOL_INVALID) { f.repo.ask(s, question(), KEY) }
    }
    @Test fun closeConflictDoesNotRetryOrPretendClosed() = runTest {
        val f = Fixture(); val s = f.create(); f.api.close = { error(409) }
        fails(KnowledgeFailure.CONFLICT) { f.repo.close(s) }
        assertEquals(listOf(0L), f.api.closes)
    }
    @Test fun malformedTimeOrUnknownEnumFromActualDeserializerMapsToProtocolFailure() = runTest {
        for ((purpose, time) in listOf("PUBLIC_KNOWLEDGE" to "private-invalid-time", "UNKNOWN_PURPOSE" to "2026-09-11T01:05:00Z")) {
            val f = Fixture()
            f.api.create = {
                Response.success(NetworkModule.provideJson().decodeFromString<AssistantSessionResponse>(
                    """{"code":"OK","message":"success","traceId":"test","data":{"id":"$ID","purpose":"$purpose","state":"OPEN","version":0,"expiresAt":"$time"}}"""))
            }
            val failure = fails(KnowledgeFailure.PROTOCOL_INVALID) { f.create() }
            assertFalse(failure.toString().contains("private-invalid-time")); assertEquals(1, f.api.creates.size)
        }
    }
    @Test fun explicitCommitUncertaintyOffersFiniteRecoveryWithoutAutomaticResubmit() = runTest {
        val f = Fixture(); val s = f.create()
        f.api.ask = { Response.error(503, """{"data":{"reasonCode":"COMMIT_UNCERTAIN"}}""".toResponseBody()) }
        val failure = fails(KnowledgeFailure.NETWORK_UNCERTAIN) { f.repo.ask(s, question(), KEY) }
        assertEquals(AssistantReasonCode.COMMIT_UNCERTAIN, failure.reason)
        assertEquals(1, f.api.asks.size); assertTrue(f.api.reads.isEmpty())
    }
    @Test fun refreshNetworkOutageIsNotMisreportedAsLoggedOut() = runTest {
        val f = Fixture(); val s = f.create(); f.api.ask = { error(401) }; f.auth.refreshIoFailure = true
        fails(KnowledgeFailure.NETWORK_UNCERTAIN) { f.repo.ask(s, question(), KEY) }
        assertTrue(f.repo.isCurrent(s)); assertEquals(1, f.api.asks.size)
    }

    private suspend fun fails(expected: KnowledgeFailure, block: suspend () -> Any?): KnowledgeException {
        try { block() } catch (ex: KnowledgeException) { assertEquals(expected, ex.failure); return ex }
        throw AssertionError("Expected finite knowledge failure")
    }
    internal class Fixture {
        val api = FakeApi(); val auth = FakeAuth()
        val repo = DefaultKnowledgeRepository(api, auth, NetworkModule.provideJson())
        suspend fun create() = repo.create(AssistantPurpose.PUBLIC_KNOWLEDGE, KEY)
    }
    internal class FakeApi : AssistantKnowledgeApi {
        val creates = mutableListOf<CreateAssistantSessionRequest>(); val asks = mutableListOf<AskAssistantQuestionRequest>()
        val reads = mutableListOf<String>(); val closes = mutableListOf<Long>()
        var create: suspend () -> Response<AssistantSessionResponse> = { sessionResponse(creates.last().purpose) }
        var ask: suspend () -> Response<AssistantQuestionResultResponse> = { resultResponse(result().copy(requestKey = asks.last().requestKey)) }
        var read: suspend () -> Response<AssistantQuestionResultResponse> = { resultResponse(result().copy(requestKey = reads.last())) }
        var close: suspend () -> Response<AssistantSessionResponse> = { Response.success(sessionResponse().body()!!.copy(data = sessionResponse().body()!!.data.copy(state = AssistantSessionState.CLOSED, version = closes.last() + 1))) }
        override suspend fun createAssistantSession(createAssistantSessionRequest: CreateAssistantSessionRequest): Response<AssistantSessionResponse> { creates += createAssistantSessionRequest; return create() }
        override suspend fun askAssistantQuestion(id: UUID, askAssistantQuestionRequest: AskAssistantQuestionRequest): Response<AssistantQuestionResultResponse> { asks += askAssistantQuestionRequest; return ask() }
        override suspend fun getAssistantQuestion(id: UUID, key: String): Response<AssistantQuestionResultResponse> { reads += key; return read() }
        override suspend fun closeAssistantSession(id: UUID, expectedVersion: Long): Response<AssistantSessionResponse> { closes += expectedVersion; return close() }
    }
    internal class FakeAuth : AuthSessionRepository {
        override var loginEpoch = 0L
        var changeEpochOnRefresh = false; var identicalRefresh = false; var refreshIoFailure = false
        override val session = MutableStateFlow<AuthSession?>(identity("owner")); var refreshes = 0; var refreshedOwner = "owner"
        override suspend fun refresh(): AuthSession {
            if (refreshIoFailure) throw IOException()
            refreshes++; if (changeEpochOnRefresh) loginEpoch++
            return (if (identicalRefresh) session.value!!.copy() else identity(refreshedOwner).copy(accessTokenExpiresAt = Instant.parse("2026-09-11T02:00:00Z"))).also { session.value = it }
        }
        override suspend fun restore() = session.value
        override suspend fun clearLocalSession() { session.value = null }
        override suspend fun loginWithWechatCode(code: String, device: WechatLoginDevice): AuthSession = error("Unused login")
    }
    companion object {
        private val ID = UUID(0, 1)
        private const val KEY = "question-key-000001"
        private fun identity(owner: String) = AuthSession(owner, "ACTIVE", null, Instant.parse("2026-09-11T01:00:00Z"), Instant.parse("2026-10-11T01:00:00Z"))
        private fun question() = KnowledgeQuestion.Text("如何使用？", "zh-CN", 1)
        private fun citation() = KnowledgeCitation("e1", ID, 1, ID, "说明", "说明", 0, 2)
        private fun candidate() = KnowledgeGraphCandidate(ID, 0, listOf("测试亲友"))
        private fun result() = AssistantQuestionResult(KEY, AssistantResultStatus.EVIDENCE_ONLY, AssistantAnswerMode.EXTRACTIVE,
            AssistantReasonCode.NONE, "说明", listOf(citation()), emptyList(), 2, KnowledgeRetrievalMode.KEYWORD_ONLY)
        private fun sessionResponse(purpose: AssistantPurpose = AssistantPurpose.PUBLIC_KNOWLEDGE) = Response.success(AssistantSessionResponse("OK", "success",
            AssistantSession(ID, purpose, AssistantSessionState.OPEN, 0, OffsetDateTime.parse("2026-09-11T01:05:00Z")), "test"))
        private fun resultResponse(result: AssistantQuestionResult) = Response.success(AssistantQuestionResultResponse("OK", "success", result, "test"))
        private fun <T> error(status: Int): Response<T> = Response.error(status, "{}".toResponseBody())
    }
}
