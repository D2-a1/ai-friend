package com.aifriend.feature.settings

import com.aifriend.contract.api.VoiceTemplatesApi
import com.aifriend.contract.model.AliasCompatibility
import com.aifriend.contract.model.ConfirmedDeletionRequest
import com.aifriend.contract.model.RoutineCommandIntent
import com.aifriend.contract.model.RoutineTemplateDeletion
import com.aifriend.contract.model.RoutineTemplateDeletionResponse
import com.aifriend.contract.model.SafetyCommandEnrollmentRequest
import com.aifriend.contract.model.SafetyCommandEnrollmentResponse
import com.aifriend.contract.model.SafetyCommandType
import com.aifriend.contract.model.VoiceTemplateListResponse
import com.aifriend.contract.model.VoiceTemplateSummary
import com.aifriend.core.voice.LocalRoutineCommandTemplateStore
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
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class DefaultRoutineCommandDeletionRepositoryTest {

    @Test
    fun refreshesOnceWithSameRequestThenClearsOnlyLocalRoutineCategory() = runTest {
        val api = FakeVoiceTemplatesApi(
            mutableListOf(Response.error(401, "".toResponseBody()), successResponse()),
        )
        val auth = FakeAuthRepository()
        val localStore = FakeLocalRoutineCommandTemplateStore()
        val repository = DefaultRoutineCommandDeletionRepository(api, auth, localStore)

        val deletedCount = repository.clear()

        assertEquals(2, deletedCount)
        assertEquals(1, auth.refreshCount)
        assertEquals(2, api.keys.size)
        assertEquals(api.keys[0], api.keys[1])
        assertEquals(listOf(true, true), api.requests.map { it.confirmed })
        assertTrue(api.requests.all { it.expectedVersion == null })
        assertEquals(1, localStore.clearCount)
    }

    @Test
    fun doesNotClearLocalCategoryWhenServerDeletionIsUnproven() = runTest {
        val api = FakeVoiceTemplatesApi(
            mutableListOf(Response.error(409, "".toResponseBody())),
        )
        val localStore = FakeLocalRoutineCommandTemplateStore()
        val repository = DefaultRoutineCommandDeletionRepository(
            api,
            FakeAuthRepository(),
            localStore,
        )

        assertTrue(runCatching { repository.clear() }.isFailure)
        assertEquals(0, localStore.clearCount)
    }

    @Test
    fun listsOnlyRoutineActionsAsCountsAndCompatibility() = runTest {
        val api = FakeVoiceTemplatesApi(
            listResponses = mutableListOf(voiceTemplateListResponse()),
        )
        val repository = DefaultRoutineCommandDeletionRepository(
            api,
            FakeAuthRepository(),
            FakeLocalRoutineCommandTemplateStore(),
        )

        val result = repository.list()

        assertEquals(3, result.totalCount)
        assertEquals(1, result.sendMessageCount)
        assertEquals(1, result.voiceCallCount)
        assertEquals(1, result.videoCallCount)
        assertEquals(2, result.compatibleCount)
        assertEquals(1, result.incompatibleCount)
    }

    private class FakeVoiceTemplatesApi(
        private val responses: MutableList<Response<RoutineTemplateDeletionResponse>> =
            mutableListOf(),
        private val listResponses: MutableList<Response<VoiceTemplateListResponse>> =
            mutableListOf(),
    ) : VoiceTemplatesApi {
        val keys = mutableListOf<String>()
        val requests = mutableListOf<ConfirmedDeletionRequest>()

        override suspend fun deleteMyRoutineCommandTemplates(
            idempotencyKey: String,
            confirmedDeletionRequest: ConfirmedDeletionRequest,
        ): Response<RoutineTemplateDeletionResponse> {
            keys += idempotencyKey
            requests += confirmedDeletionRequest
            return responses.removeFirst()
        }

        override suspend fun enrollSafetyCommands(
            idempotencyKey: String,
            safetyCommandEnrollmentRequest: SafetyCommandEnrollmentRequest,
        ): Response<SafetyCommandEnrollmentResponse> = error("unused")

        override suspend fun listMyVoiceTemplates(): Response<VoiceTemplateListResponse> =
            listResponses.removeFirst()
    }

    private class FakeLocalRoutineCommandTemplateStore : LocalRoutineCommandTemplateStore {
        var clearCount = 0

        override suspend fun clearRoutineCommands() {
            clearCount++
        }
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
                accessTokenExpiresAt = Instant.parse("2026-08-23T05:00:00Z"),
                refreshTokenExpiresAt = Instant.parse("2026-09-23T05:00:00Z"),
            )
        }

        override suspend fun clearLocalSession() = Unit
    }

    private companion object {
        fun successResponse(): Response<RoutineTemplateDeletionResponse> = Response.success(
            RoutineTemplateDeletionResponse(
                code = RoutineTemplateDeletionResponse.Code.OK,
                message = "success",
                data = RoutineTemplateDeletion(
                    deletedCount = 2,
                    deletedAt = OffsetDateTime.parse("2026-08-23T04:00:00Z"),
                ),
                traceId = "trace",
            ),
        )

        fun voiceTemplateListResponse(): Response<VoiceTemplateListResponse> = Response.success(
            VoiceTemplateListResponse(
                code = VoiceTemplateListResponse.Code.OK,
                message = "success",
                data = listOf(
                    routineSummary(
                        RoutineCommandIntent.SEND_MESSAGE,
                        AliasCompatibility.COMPATIBLE,
                    ),
                    routineSummary(
                        RoutineCommandIntent.VOICE_CALL,
                        AliasCompatibility.COMPATIBLE,
                    ),
                    routineSummary(
                        RoutineCommandIntent.VIDEO_CALL,
                        AliasCompatibility.INCOMPATIBLE,
                    ),
                    VoiceTemplateSummary(
                        templateId = "vt_safety",
                        category = VoiceTemplateSummary.Category.SAFETY_COMMAND,
                        dialectCode = "zh-Hans-CN-x-wugang",
                        dialectPackageVersion = "dialect-v1",
                        modelVersion = "mfcc-v1",
                        thresholdVersion = "threshold-v1",
                        compatibility = AliasCompatibility.COMPATIBLE,
                        updatedAt = OffsetDateTime.parse("2026-08-24T00:00:00Z"),
                        safetyCommandType = SafetyCommandType.CANCEL,
                    ),
                ),
                traceId = "trace-list",
            ),
        )

        private fun routineSummary(
            intent: RoutineCommandIntent,
            compatibility: AliasCompatibility,
        ) = VoiceTemplateSummary(
            templateId = "vt_${intent.value.lowercase()}",
            category = VoiceTemplateSummary.Category.ROUTINE_COMMAND,
            dialectCode = "zh-Hans-CN-x-wugang",
            dialectPackageVersion = "dialect-v1",
            modelVersion = "mfcc-v1",
            thresholdVersion = "threshold-v1",
            compatibility = compatibility,
            updatedAt = OffsetDateTime.parse("2026-08-24T00:00:00Z"),
            routineCommandIntent = intent,
        )
    }
}
