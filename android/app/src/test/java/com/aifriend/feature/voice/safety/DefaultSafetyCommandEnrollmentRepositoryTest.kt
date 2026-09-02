package com.aifriend.feature.voice.safety

import com.aifriend.contract.api.VoiceTemplatesApi
import com.aifriend.contract.model.AliasCompatibility
import com.aifriend.contract.model.ConfirmedDeletionRequest
import com.aifriend.contract.model.RoutineTemplateDeletionResponse
import com.aifriend.contract.model.SafetyCommandEnrollmentRequest
import com.aifriend.contract.model.SafetyCommandEnrollmentResponse
import com.aifriend.contract.model.SafetyCommandEnrollmentResult
import com.aifriend.contract.model.SafetyCommandType
import com.aifriend.contract.model.VoiceTemplateListResponse
import com.aifriend.contract.model.VoiceTemplateSummary
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
import org.junit.Test
import retrofit2.Response

/**
 * 安全指令网络仓库的幂等重试与完整响应校验测试。
 *
 * @author codex
 * @since 2026-08-13
 */
class DefaultSafetyCommandEnrollmentRepositoryTest {

    @Test
    fun unauthorizedRefreshesOnceWithSameIdempotencyKeyAndBody() = runTest {
        val api = FakeVoiceTemplatesApi(unauthorizedFirst = true)
        val auth = FakeAuthSessionRepository()
        val repository = DefaultSafetyCommandEnrollmentRepository(api, auth)
        val commands = commandObjects()

        repository.enroll(commands, "voice-template-v1")

        assertEquals(1, auth.refreshCount)
        assertEquals(2, api.calls.size)
        assertEquals(api.calls[0], api.calls[1])
        assertEquals("voice-template-v1", api.calls.first().request.consentPolicyVersion)
        assertEquals(SafetyCommandType.CONFIRM_SEND, api.calls.first().request.commands.first().type)
    }

    private data class Call(
        val idempotencyKey: String,
        val request: SafetyCommandEnrollmentRequest,
    )

    private class FakeVoiceTemplatesApi(
        private val unauthorizedFirst: Boolean,
    ) : VoiceTemplatesApi {
        val calls = mutableListOf<Call>()

        override suspend fun enrollSafetyCommands(
            idempotencyKey: String,
            safetyCommandEnrollmentRequest: SafetyCommandEnrollmentRequest,
        ): Response<SafetyCommandEnrollmentResponse> {
            calls += Call(idempotencyKey, safetyCommandEnrollmentRequest)
            if (unauthorizedFirst && calls.size == 1) {
                return Response.error(401, byteArrayOf().toResponseBody())
            }
            val now = OffsetDateTime.parse("2026-08-13T01:00:00Z")
            return Response.success(
                SafetyCommandEnrollmentResponse(
                    code = SafetyCommandEnrollmentResponse.Code.OK,
                    message = "success",
                    data = SafetyCommandEnrollmentResult(
                        complete = true,
                        templates = SafetyCommandType.entries.mapIndexed { index, type ->
                            VoiceTemplateSummary(
                                templateId = "vt_$index",
                                category = VoiceTemplateSummary.Category.SAFETY_COMMAND,
                                dialectCode = "zh-Hans-CN-x-wugang",
                                dialectPackageVersion = "test-v1",
                                modelVersion = "test-v1",
                                thresholdVersion = "test-v1",
                                compatibility = AliasCompatibility.COMPATIBLE,
                                updatedAt = now,
                                safetyCommandType = type,
                            )
                        },
                    ),
                    traceId = "trace",
                ),
            )
        }

        override suspend fun listMyVoiceTemplates(): Response<VoiceTemplateListResponse> =
            error("not used")

        override suspend fun deleteMyRoutineCommandTemplates(
            idempotencyKey: String,
            confirmedDeletionRequest: ConfirmedDeletionRequest,
        ): Response<RoutineTemplateDeletionResponse> = error("not used")
    }

    private class FakeAuthSessionRepository : AuthSessionRepository {
        override val session: StateFlow<AuthSession?> = MutableStateFlow(null)
        var refreshCount = 0

        override suspend fun restore(): AuthSession? = null

        override suspend fun loginWithWechatCode(
            code: String,
            device: WechatLoginDevice,
        ): AuthSession = error("not used")

        override suspend fun refresh(): AuthSession {
            refreshCount++
            return AuthSession(
                userId = "us_0123456789abcdef0123456789abcdef",
                userStatus = "ACTIVE",
                displayName = null,
                accessTokenExpiresAt = Instant.parse("2026-08-13T02:00:00Z"),
                refreshTokenExpiresAt = Instant.parse("2026-09-13T01:00:00Z"),
            )
        }

        override suspend fun clearLocalSession() = Unit
    }

    private companion object {
        fun commandObjects(): List<SafetyCommandAudioObjects> = SafetyCommandType.entries.mapIndexed {
                index,
                type,
            ->
            SafetyCommandAudioObjects(
                type = type,
                firstAudioObjectId = "au_${index}_first",
                secondAudioObjectId = "au_${index}_second",
            )
        }
    }
}
