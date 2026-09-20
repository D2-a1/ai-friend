package com.aifriend.feature.privacy

import com.aifriend.contract.api.PrivacyApi
import com.aifriend.contract.model.AccountClosure
import com.aifriend.contract.model.AccountClosureResponse
import com.aifriend.contract.model.ConfirmedAccountClosureRequest
import com.aifriend.contract.model.ConfirmedTaskHistoryDeletionRequest
import com.aifriend.contract.model.DeletePersonalMemoryRequest
import com.aifriend.contract.model.PersonalMemoryResponse
import com.aifriend.contract.model.UpdatePersonalMemoryRequest
import com.aifriend.contract.model.ConsentListResponse
import com.aifriend.contract.model.ConsentResponse
import com.aifriend.contract.model.ConsentType
import com.aifriend.contract.model.TaskHistoryDeletionResponse
import com.aifriend.contract.model.UpdateConsentRequest
import com.aifriend.core.network.ApiErrorCodeReader
import com.aifriend.feature.auth.AuthSession
import com.aifriend.feature.auth.AuthSessionRepository
import com.aifriend.feature.auth.WechatLoginDevice
import java.time.OffsetDateTime
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class DefaultAccountClosureRepositoryTest {

    @Test
    fun refreshesOnceWithSameKeyAndBodyAfterUnauthorized() = runTest {
        val api = FakePrivacyApi(
            mutableListOf(Response.error(401, "".toResponseBody()), successResponse()),
        )
        val auth = FakeAuthRepository()
        val repository = DefaultAccountClosureRepository(api, auth, ApiErrorCodeReader(Json))

        val result = repository.close()

        assertEquals(AccountClosureAcceptance.Accepted, result)
        assertEquals(1, auth.refreshCount)
        assertEquals(2, api.keys.size)
        assertEquals(api.keys[0], api.keys[1])
        assertEquals(listOf(true, true), api.confirmedValues)
    }

    @Test
    fun treatsOnlyStableAccountClosureCodeAsRecoveredAcceptance() = runTest {
        val errorJson = """{
            "code":"ACCOUNT_CLOSURE_ACCEPTED",
            "message":"注销申请已经受理",
            "traceId":"trace"
        }""".trimIndent()
        val api = FakePrivacyApi(
            mutableListOf(Response.error(409, errorJson.toResponseBody("application/json".toMediaType()))),
        )
        val repository = DefaultAccountClosureRepository(
            api, FakeAuthRepository(), ApiErrorCodeReader(Json),
        )

        assertEquals(AccountClosureAcceptance.Recovered, repository.close())
    }

    @Test
    fun ordinaryConflictIsNotMisreportedAsAccepted() = runTest {
        val errorJson = """{
            "code":"SESSION_CONFLICT",
            "message":"冲突",
            "traceId":"trace"
        }""".trimIndent()
        val api = FakePrivacyApi(
            mutableListOf(Response.error(409, errorJson.toResponseBody("application/json".toMediaType()))),
        )
        val repository = DefaultAccountClosureRepository(
            api, FakeAuthRepository(), ApiErrorCodeReader(Json),
        )

        assertTrue(runCatching { repository.close() }.isFailure)
    }

    private class FakePrivacyApi(
        private val responses: MutableList<Response<AccountClosureResponse>>,
    ) : PrivacyApi {
        val keys = mutableListOf<String>()
        val confirmedValues = mutableListOf<Boolean>()

        override suspend fun closeMyAccount(
            idempotencyKey: String,
            confirmedAccountClosureRequest: ConfirmedAccountClosureRequest,
        ): Response<AccountClosureResponse> {
            keys += idempotencyKey
            confirmedValues += confirmedAccountClosureRequest.confirmed
            return responses.removeFirst()
        }

        override suspend fun clearMyTaskHistory(
            idempotencyKey: String,
            confirmedTaskHistoryDeletionRequest: ConfirmedTaskHistoryDeletionRequest,
        ): Response<TaskHistoryDeletionResponse> = error("unused")

        override suspend fun getMyTaskHistoryDeletion(): Response<TaskHistoryDeletionResponse> =
            error("unused")

        override suspend fun deleteMyPersonalMemory(
            idempotencyKey: String,
            deletePersonalMemoryRequest: DeletePersonalMemoryRequest,
        ): Response<PersonalMemoryResponse> = error("unused")

        override suspend fun getMyPersonalMemory(): Response<PersonalMemoryResponse> =
            error("unused")

        override suspend fun updateMyPersonalMemory(
            idempotencyKey: String,
            updatePersonalMemoryRequest: UpdatePersonalMemoryRequest,
        ): Response<PersonalMemoryResponse> = error("unused")

        override suspend fun listMyConsents(): Response<ConsentListResponse> = error("unused")

        override suspend fun updateMyConsent(
            type: ConsentType,
            idempotencyKey: String,
            updateConsentRequest: UpdateConsentRequest,
        ): Response<ConsentResponse> = error("unused")
    }

    private class FakeAuthRepository : AuthSessionRepository {
        override val session: StateFlow<AuthSession?> = MutableStateFlow(null)
        var refreshCount = 0
        override suspend fun restore(): AuthSession? = null
        override suspend fun loginWithWechatCode(
            code: String,
            device: WechatLoginDevice,
        ): AuthSession = error("unused")
        override suspend fun refresh(): AuthSession {
            refreshCount++
            return AuthSession(
                userId = "us_0123456789abcdef0123456789abcdef",
                userStatus = "ACTIVE",
                displayName = null,
                accessTokenExpiresAt = Instant.parse("2026-08-20T03:00:00Z"),
                refreshTokenExpiresAt = Instant.parse("2026-09-20T03:00:00Z"),
            )
        }
        override suspend fun clearLocalSession() = Unit
    }

    private companion object {
        fun successResponse(): Response<AccountClosureResponse> = Response.success(
            AccountClosureResponse(
                code = AccountClosureResponse.Code.OK,
                message = "success",
                data = AccountClosure(
                    acceptedAt = OffsetDateTime.parse("2026-08-20T02:00:00Z"),
                    reRegistrationNotBefore = OffsetDateTime.parse("2026-08-23T02:00:00Z"),
                ),
                traceId = "trace",
            ),
        )
    }
}
