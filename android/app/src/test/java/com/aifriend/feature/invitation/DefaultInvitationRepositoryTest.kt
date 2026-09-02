package com.aifriend.feature.invitation

import com.aifriend.contract.api.InvitationsApi
import com.aifriend.contract.model.AcceptInvitationRequest
import com.aifriend.contract.model.CreateInvitationSessionRequest
import com.aifriend.contract.model.DeclineInvitationRequest
import com.aifriend.contract.model.Invitation
import com.aifriend.contract.model.InvitationDecisionResponse
import com.aifriend.contract.model.InvitationResponse
import com.aifriend.contract.model.InvitationSessionCreatedResponse
import com.aifriend.contract.model.InvitationSessionViewResponse
import com.aifriend.contract.model.PendingInvitationListResponse
import com.aifriend.contract.model.PendingInvitationSummary
import com.aifriend.feature.auth.AuthSession
import com.aifriend.feature.auth.AuthSessionRepository
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class DefaultInvitationRepositoryTest {

    @Test
    fun createRetriesOnceWithSameIdempotencyKeyAfter401() = runTest {
        val api = FakeInvitationsApi(failFirstCreate = true)
        val auth = FakeAuthSessionRepository()
        val repository = DefaultInvitationRepository(api, auth)

        val invitation = repository.create()

        assertEquals(1, auth.refreshCount)
        assertEquals(2, api.createKeys.size)
        assertEquals(api.createKeys[0], api.createKeys[1])
        assertEquals("iv_0123456789abcdef0123456789abcdef", invitation.invitationId)
        assertTrue(invitation.shareUrl.contains("#proof="))
    }

    @Test
    fun revokePassesOnlyPublicIdAndFreshIdempotencyKey() = runTest {
        val api = FakeInvitationsApi()
        val repository = DefaultInvitationRepository(api, FakeAuthSessionRepository())

        repository.revoke("iv_0123456789abcdef0123456789abcdef")

        assertEquals("iv_0123456789abcdef0123456789abcdef", api.revokedInvitationId)
        assertEquals(36, api.revokeKey?.length)
    }

    @Test
    fun listPendingRetriesOnceAfter401AndReturnsNoShareUrl() = runTest {
        val api = FakeInvitationsApi(failFirstList = true)
        val auth = FakeAuthSessionRepository()
        val repository = DefaultInvitationRepository(api, auth)

        val invitations = repository.listPending()

        assertEquals(1, auth.refreshCount)
        assertEquals(2, api.listCallCount)
        assertEquals(1, invitations.size)
        assertEquals("iv_0123456789abcdef0123456789abcdef", invitations.single().invitationId)
    }

    private class FakeInvitationsApi(
        private val failFirstCreate: Boolean = false,
        private val failFirstList: Boolean = false,
    ) : InvitationsApi {
        val createKeys = mutableListOf<String>()
        var revokedInvitationId: String? = null
        var revokeKey: String? = null
        var listCallCount = 0

        override suspend fun listContactInvitations(): Response<PendingInvitationListResponse> {
            listCallCount++
            if (failFirstList && listCallCount == 1) {
                return Response.error(
                    401,
                    "{}".toResponseBody("application/json".toMediaType()),
                )
            }
            return Response.success(
                PendingInvitationListResponse(
                    code = PendingInvitationListResponse.Code.OK,
                    message = "success",
                    data = listOf(
                        PendingInvitationSummary(
                            invitationId = "iv_0123456789abcdef0123456789abcdef",
                            expiresAt = OffsetDateTime.parse("2026-08-08T01:00:00Z"),
                            status = PendingInvitationSummary.Status.WAITING_CONFIRMATION,
                        ),
                    ),
                    traceId = "trace",
                ),
            )
        }

        override suspend fun createContactInvitation(
            idempotencyKey: String,
        ): Response<InvitationResponse> {
            createKeys += idempotencyKey
            if (failFirstCreate && createKeys.size == 1) {
                return Response.error(
                    401,
                    "{}".toResponseBody("application/json".toMediaType()),
                )
            }
            val expiresAt = OffsetDateTime.ofInstant(
                Instant.parse("2026-08-08T01:00:00Z"),
                ZoneOffset.UTC,
            )
            return Response.success(
                InvitationResponse(
                    code = InvitationResponse.Code.OK,
                    message = "success",
                    data = Invitation(
                        invitationId = "iv_0123456789abcdef0123456789abcdef",
                        shareUrl = URI.create(
                            "https://invite.example.com/invite/" +
                                "iv_0123456789abcdef0123456789abcdef#proof=secret",
                        ),
                        expiresAt = expiresAt,
                        status = Invitation.Status.WAITING,
                    ),
                    traceId = "trace",
                ),
            )
        }

        override suspend fun revokeContactInvitation(
            invitationId: String,
            idempotencyKey: String,
        ): Response<Unit> {
            revokedInvitationId = invitationId
            revokeKey = idempotencyKey
            return Response.success(Unit)
        }

        override suspend fun acceptPublicContactInvitation(
            idempotencyKey: String,
            xCSRFToken: String,
            acceptInvitationRequest: AcceptInvitationRequest,
        ): Response<InvitationDecisionResponse> = error("not used")

        override suspend fun createPublicContactInvitationSession(
            createInvitationSessionRequest: CreateInvitationSessionRequest,
        ): Response<InvitationSessionCreatedResponse> = error("not used")

        override suspend fun completeWechatInvitationOAuth(
            code: String,
            state: String,
        ): Response<Unit> = error("browser only")

        override suspend fun declinePublicContactInvitation(
            idempotencyKey: String,
            xCSRFToken: String,
            declineInvitationRequest: DeclineInvitationRequest,
        ): Response<Unit> = error("not used")

        override suspend fun getCurrentPublicContactInvitationSession():
            Response<InvitationSessionViewResponse> = error("not used")
    }

    private class FakeAuthSessionRepository : AuthSessionRepository {
        override val session: StateFlow<AuthSession?> = MutableStateFlow(null)
        var refreshCount = 0

        override suspend fun restore(): AuthSession? = null

        override suspend fun loginWithWechatCode(
            code: String,
            device: com.aifriend.feature.auth.WechatLoginDevice,
        ): AuthSession = error("not used")

        override suspend fun refresh(): AuthSession {
            refreshCount++
            return AuthSession(
                userId = "us_0123456789abcdef0123456789abcdef",
                userStatus = "ACTIVE",
                displayName = null,
                accessTokenExpiresAt = Instant.parse("2026-08-07T01:15:00Z"),
                refreshTokenExpiresAt = Instant.parse("2026-09-07T01:00:00Z"),
            )
        }

        override suspend fun clearLocalSession() = Unit
    }
}
