package com.aifriend.feature.personalization

import com.aifriend.contract.api.PrivacyApi
import com.aifriend.contract.model.AccountClosureResponse
import com.aifriend.contract.model.AmbiguousCallPreference
import com.aifriend.contract.model.ConfirmedAccountClosureRequest
import com.aifriend.contract.model.ConfirmedTaskHistoryDeletionRequest
import com.aifriend.contract.model.ConsentListResponse
import com.aifriend.contract.model.ConsentResponse
import com.aifriend.contract.model.ConsentType
import com.aifriend.contract.model.DeletePersonalMemoryRequest
import com.aifriend.contract.model.DialogueStylePreference
import com.aifriend.contract.model.PersonalMemory
import com.aifriend.contract.model.PersonalMemoryPreferences
import com.aifriend.contract.model.PersonalMemoryResponse
import com.aifriend.contract.model.SpeechRatePreference as ContractSpeechRate
import com.aifriend.contract.model.TaskHistoryDeletionResponse
import com.aifriend.contract.model.UpdateConsentRequest
import com.aifriend.contract.model.UpdatePersonalMemoryRequest
import com.aifriend.core.settings.SpeechRatePreference
import com.aifriend.feature.auth.AuthSession
import com.aifriend.feature.auth.AuthSessionRepository
import com.aifriend.feature.auth.WechatLoginDevice
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class DefaultPersonalMemoryRepositoryTest {

    @Test
    fun readCachesOnlyActiveConsentedFiniteChoices() = runTest {
        val api = FakePrivacyApi(getResponses = mutableListOf(success(activeMemory())))
        val repository = DefaultPersonalMemoryRepository(api, FakeAuthRepository())

        val snapshot = repository.read()

        assertTrue(snapshot.featureEnabled)
        assertTrue(snapshot.consentGranted)
        assertEquals(3L, snapshot.version)
        assertEquals(
            PersonalMemoryChoices(
                speechRate = SpeechRatePreference.SLOW,
                dialogueStyle = DialogueStyleChoice.BRIEF,
                ambiguousCall = AmbiguousCallChoice.VIDEO,
            ),
            repository.currentPromptChoices(),
        )
    }

    @Test
    fun disabledOrRevokedSnapshotAlwaysUsesSafeDefault() = runTest {
        val api = FakePrivacyApi(
            getResponses = mutableListOf(
                success(activeMemory(featureEnabled = false)),
                success(activeMemory(consentGranted = false)),
            ),
        )
        val repository = DefaultPersonalMemoryRepository(api, FakeAuthRepository())

        repository.read()
        assertEquals(PersonalMemoryChoices.SafeDefault, repository.currentPromptChoices())
        repository.read()
        assertEquals(PersonalMemoryChoices.SafeDefault, repository.currentPromptChoices())
    }

    @Test
    fun updateRetriesUnauthorizedWithSameKeyAndBody() = runTest {
        val updated = activeMemory().copy(version = 4L)
        val api = FakePrivacyApi(
            updateResponses = mutableListOf(
                Response.error(401, "".toResponseBody()),
                success(updated),
            ),
        )
        val auth = FakeAuthRepository()
        val repository = DefaultPersonalMemoryRepository(api, auth)
        val choices = PersonalMemoryChoices(
            speechRate = SpeechRatePreference.FAST,
            dialogueStyle = DialogueStyleChoice.STANDARD,
            ambiguousCall = AmbiguousCallChoice.VOICE,
        )

        val result = repository.update(choices, expectedVersion = 3L)

        assertEquals(1, auth.refreshCount)
        assertEquals(2, api.updateKeys.size)
        assertEquals(api.updateKeys[0], api.updateKeys[1])
        assertEquals(api.updateRequests[0], api.updateRequests[1])
        assertEquals(4L, result.version)
    }

    @Test
    fun uncertainUpdateInvalidatesPreviouslyCachedPreference() = runTest {
        val api = FakePrivacyApi(
            getResponses = mutableListOf(success(activeMemory())),
            updateResponses = mutableListOf(Response.error(409, "".toResponseBody())),
        )
        val repository = DefaultPersonalMemoryRepository(api, FakeAuthRepository())
        repository.read()

        val result = runCatching {
            repository.update(PersonalMemoryChoices.SafeDefault, expectedVersion = 3L)
        }

        assertTrue(result.isFailure)
        assertEquals(PersonalMemoryChoices.SafeDefault, repository.currentPromptChoices())
    }

    @Test
    fun deleteSendsExplicitConfirmationAndClearsPromptCache() = runTest {
        val deleted = activeMemory().copy(preferences = null, version = 4L)
        val api = FakePrivacyApi(deleteResponses = mutableListOf(success(deleted)))
        val repository = DefaultPersonalMemoryRepository(api, FakeAuthRepository())

        val result = repository.delete(expectedVersion = 3L)

        assertNull(result.choices)
        assertEquals(true, api.deleteRequests.single().confirmed)
        assertEquals(3L, api.deleteRequests.single().expectedVersion)
        assertEquals(PersonalMemoryChoices.SafeDefault, repository.currentPromptChoices())
    }

    private class FakePrivacyApi(
        private val getResponses: MutableList<Response<PersonalMemoryResponse>> = mutableListOf(),
        private val updateResponses: MutableList<Response<PersonalMemoryResponse>> =
            mutableListOf(),
        private val deleteResponses: MutableList<Response<PersonalMemoryResponse>> =
            mutableListOf(),
    ) : PrivacyApi {
        val updateKeys = mutableListOf<String>()
        val updateRequests = mutableListOf<UpdatePersonalMemoryRequest>()
        val deleteRequests = mutableListOf<DeletePersonalMemoryRequest>()

        override suspend fun getMyPersonalMemory(): Response<PersonalMemoryResponse> =
            getResponses.removeFirst()

        override suspend fun updateMyPersonalMemory(
            idempotencyKey: String,
            updatePersonalMemoryRequest: UpdatePersonalMemoryRequest,
        ): Response<PersonalMemoryResponse> {
            updateKeys += idempotencyKey
            updateRequests += updatePersonalMemoryRequest
            return updateResponses.removeFirst()
        }

        override suspend fun deleteMyPersonalMemory(
            idempotencyKey: String,
            deletePersonalMemoryRequest: DeletePersonalMemoryRequest,
        ): Response<PersonalMemoryResponse> {
            deleteRequests += deletePersonalMemoryRequest
            return deleteResponses.removeFirst()
        }

        override suspend fun clearMyTaskHistory(
            idempotencyKey: String,
            confirmedTaskHistoryDeletionRequest: ConfirmedTaskHistoryDeletionRequest,
        ): Response<TaskHistoryDeletionResponse> = error("unused")

        override suspend fun closeMyAccount(
            idempotencyKey: String,
            confirmedAccountClosureRequest: ConfirmedAccountClosureRequest,
        ): Response<AccountClosureResponse> = error("unused")

        override suspend fun getMyTaskHistoryDeletion(): Response<TaskHistoryDeletionResponse> =
            error("unused")

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
                accessTokenExpiresAt = Instant.parse("2026-09-04T01:00:00Z"),
                refreshTokenExpiresAt = Instant.parse("2026-10-04T01:00:00Z"),
            )
        }

        override suspend fun clearLocalSession() = Unit
    }

    private companion object {
        fun activeMemory(
            featureEnabled: Boolean = true,
            consentGranted: Boolean = true,
        ) = PersonalMemory(
            featureEnabled = featureEnabled,
            consentGranted = consentGranted,
            policyVersion = "personal-memory-v1",
            preferences = PersonalMemoryPreferences(
                speechRate = ContractSpeechRate.SLOW,
                dialogueStyle = DialogueStylePreference.BRIEF,
                ambiguousCall = AmbiguousCallPreference.VIDEO,
            ),
            version = 3L,
            updatedAt = OffsetDateTime.parse("2026-09-04T00:00:00Z"),
        )

        fun success(memory: PersonalMemory): Response<PersonalMemoryResponse> =
            Response.success(
                PersonalMemoryResponse(
                    code = PersonalMemoryResponse.Code.OK,
                    message = "success",
                    data = memory,
                    traceId = "trace",
                ),
            )
    }
}