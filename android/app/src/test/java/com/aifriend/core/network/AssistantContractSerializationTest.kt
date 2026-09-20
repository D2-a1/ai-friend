package com.aifriend.core.network

import com.aifriend.contract.api.AssistantKnowledgeApi
import com.aifriend.contract.api.KnowledgeAdminApi
import com.aifriend.contract.model.*
import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import org.junit.Assert.*
import org.junit.Test
import retrofit2.http.DELETE
import retrofit2.http.Query
import retrofit2.http.GET

/** 生成契约、生产序列化模块与Retrofit注解；不连接HTTP或云端。 */
class AssistantContractSerializationTest {
    private val json = NetworkModule.provideJson()
    private val id = "00000000-0000-0000-0000-000000000001"

    @Test fun sessionResponseDecodesPurposeStateUuidAndTime() {
        val response = json.decodeFromString<AssistantSessionResponse>(
            """{"code":"OK","message":"success","traceId":"test","data":{"id":"$id","purpose":"PUBLIC_KNOWLEDGE","state":"OPEN","version":0,"expiresAt":"2026-09-10T10:05:00Z"}}""",
        )
        assertEquals(UUID.fromString(id), response.data.id)
        assertEquals(AssistantPurpose.PUBLIC_KNOWLEDGE, response.data.purpose)
        assertEquals(AssistantSessionState.OPEN, response.data.state)
    }

    @Test fun resultResponsePreservesActualModeAndCitation() {
        val response = json.decodeFromString<AssistantQuestionResultResponse>(
            """{"code":"OK","message":"success","traceId":"test","data":{"requestKey":"request-key-000001","status":"EVIDENCE_ONLY","answerMode":"EXTRACTIVE","reasonCode":"NONE","text":"说明","citations":[{"evidenceId":"e1","documentId":"$id","documentVersion":1,"chunkId":"$id","title":"未命名片段","text":"说明","sourceStart":0,"sourceEnd":2}],"candidates":[],"version":2,"retrievalMode":"HYBRID"}}""",
        )
        assertEquals(AssistantResultStatus.EVIDENCE_ONLY, response.data.status)
        assertEquals(AssistantAnswerMode.EXTRACTIVE, response.data.answerMode)
        assertEquals(KnowledgeRetrievalMode.HYBRID, response.data.retrievalMode)
        assertEquals(UUID.fromString(id), response.data.citations.single().documentId)
    }

    @Test fun graphQueryEncodesFiniteEnumAndUuid() {
        val encoded = json.encodeToString(KnowledgeGraphQuery(KnowledgeGraphQueryType.LIST_ALIASES, UUID.fromString(id)))
        assertTrue(encoded.contains("\"LIST_ALIASES\""))
        assertTrue(encoded.contains(id))
        assertFalse(encoded.contains("aliasText"))
    }

    @Test fun managementContractDistinguishesContentVersionFromDeletionRevision() {
        val response = json.decodeFromString<KnowledgeImportJobResponse>(
            """{"code":"OK","message":"success","traceId":"test","data":{"id":"$id","status":"PENDING","attempts":0,"errorCode":"NONE","documentId":"$id","documentVersion":2,"documentRevision":5}}""",
        )
        assertEquals(KnowledgeImportState.PENDING, response.data.status)
        assertEquals(2L, response.data.documentVersion)
        assertEquals(5L, response.data.documentRevision)
    }

    @Test fun shortUuidIsRejectedWithoutEchoingInput() {
        try {
            json.decodeFromString<KnowledgeGraphQuery>("""{"queryType":"LIST_ALIASES","contactId":"1-1-1-1-1"}""")
            fail("Must reject shortened UUID")
        } catch (expected: SerializationException) {
            assertEquals("INVALID_UUID", expected.message)
        }
    }

    @Test fun closeVersionRemainsQueryParameterNotRequestBody() {
        val method = AssistantKnowledgeApi::class.java.methods.single { it.name == "closeAssistantSession" }
        assertEquals("assistant/sessions/{id}", requireNotNull(method.getAnnotation(DELETE::class.java)).value)
        assertTrue(method.parameterAnnotations.flatten().filterIsInstance<Query>().any { it.value == "expectedVersion" })
    }

    @Test fun cleanupManagementDecodesPendingAlertsAndNullFailureWithoutClaimingPurge() {
        val response = json.decodeFromString<KnowledgeCleanupStatusResponse>(
            """{"code":"OK","message":"success","traceId":"test","data":{"state":"CLEANUP_PENDING","observedAt":"2026-09-12T12:00:00Z","firstFailureAt":null,"nextAttemptAt":"2026-09-12T12:00:00Z","failures":0,"leaseState":"EXPIRED","inactiveVersions":10000,"inactiveGenerations":1,"unreferencedChunks":2,"countsTruncated":true,"pendingFirstAlerts":1,"pendingOverdueAlerts":1,"overdue":false,"localScanEnabled":false}}""",
        )
        assertEquals(KnowledgeCleanupBacklogState.CLEANUP_PENDING, response.data.state)
        assertEquals(KnowledgeCleanupLeaseState.EXPIRED, response.data.leaseState)
        assertNull(response.data.firstFailureAt)
        assertTrue(response.data.countsTruncated)
        assertFalse(response.data.localScanEnabled)
        assertEquals(10000L, response.data.inactiveVersions)
        assertEquals(1L, response.data.pendingOverdueAlerts)
        val method = KnowledgeAdminApi::class.java.methods.single { it.name == "getKnowledgeCleanupStatus" }
        assertEquals("admin/knowledge/cleanup-status", requireNotNull(method.getAnnotation(GET::class.java)).value)
    }
    @Test fun enabledMaintenanceDoesNotImplyCompletedCleanup() {
        val response = json.decodeFromString<KnowledgeCleanupStatusResponse>(
            """{"code":"OK","message":"success","traceId":"test","data":{"state":"CLEANUP_PENDING","observedAt":"2026-09-12T12:00:00Z","firstFailureAt":null,"nextAttemptAt":"2026-09-12T12:00:00Z","failures":0,"leaseState":"NONE","inactiveVersions":1,"inactiveGenerations":0,"unreferencedChunks":0,"countsTruncated":false,"pendingFirstAlerts":0,"pendingOverdueAlerts":0,"overdue":false,"localScanEnabled":true}}""",
        )
        assertTrue(response.data.localScanEnabled)
        assertEquals(KnowledgeCleanupBacklogState.CLEANUP_PENDING, response.data.state)
        assertEquals(1L, response.data.inactiveVersions)
    }
}
