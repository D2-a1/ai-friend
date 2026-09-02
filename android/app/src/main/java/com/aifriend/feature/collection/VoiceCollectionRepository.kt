package com.aifriend.feature.collection

import com.aifriend.contract.api.VoiceCollectionApi
import com.aifriend.contract.model.ConsentDecision
import com.aifriend.contract.model.CreateVoiceCollectionSampleRequest
import com.aifriend.contract.model.DeleteVoiceCollectionSampleRequest
import com.aifriend.contract.model.UpdateVoiceCollectionTrainingAuthorizationRequest
import com.aifriend.contract.model.VoiceCollectionCategory
import com.aifriend.contract.model.VoiceCollectionDeletion
import com.aifriend.contract.model.VoiceCollectionEnvironment
import com.aifriend.contract.model.VoiceCollectionSample
import com.aifriend.contract.model.VoiceCollectionTrainingAuthorization
import com.aifriend.feature.auth.AuthApiException
import com.aifriend.feature.auth.AuthSessionRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import retrofit2.Response

/**
 * 封闭测试语音采集网络仓库。
 *
 * 写请求的幂等键和正文会在一次 401 刷新中保持不变；其他失败不自动重试。
 *
 * @author codex
 * @since 2026-08-20
 */
@Singleton
class VoiceCollectionRepository @Inject constructor(
    private val api: VoiceCollectionApi,
    private val authSessionRepository: AuthSessionRepository,
) {

    suspend fun list(): List<VoiceCollectionSample> = executeAuthenticated(
        request = { api.listVoiceCollectionSamples() },
        body = { it.data },
    )

    suspend fun create(
        audioObjectId: String,
        category: VoiceCollectionCategory,
        promptCode: String,
        environment: VoiceCollectionEnvironment,
        reviewedTranscript: String,
    ): VoiceCollectionSample {
        val idempotencyKey = UUID.randomUUID().toString()
        val requestBody = CreateVoiceCollectionSampleRequest(
            audioObjectId = audioObjectId,
            category = category,
            promptCode = promptCode,
            environment = environment,
            dialectCode = DIALECT_CODE,
            consentPolicyVersion =
                CreateVoiceCollectionSampleRequest.ConsentPolicyVersion.TEST_VOICE_COLLECTION_V1,
            reviewedTranscript = reviewedTranscript,
            reviewConfirmed = true,
            reviewPolicyVersion =
                CreateVoiceCollectionSampleRequest.ReviewPolicyVersion.VOICE_SAMPLE_REVIEW_V1,
        )
        return executeAuthenticated(
            request = { api.createVoiceCollectionSample(idempotencyKey, requestBody) },
            body = { it.data },
        )
    }

    suspend fun delete(sampleId: String, expectedVersion: Long): VoiceCollectionDeletion {
        val idempotencyKey = UUID.randomUUID().toString()
        val requestBody = DeleteVoiceCollectionSampleRequest(
            confirmed = true,
            expectedVersion = expectedVersion,
        )
        return executeAuthenticated(
            request = { api.deleteVoiceCollectionSample(sampleId, idempotencyKey, requestBody) },
            body = { it.data },
        )
    }

    suspend fun updateTrainingAuthorization(
        sampleId: String,
        expectedVersion: Long,
        decision: ConsentDecision,
    ): VoiceCollectionTrainingAuthorization {
        val idempotencyKey = UUID.randomUUID().toString()
        val requestBody = UpdateVoiceCollectionTrainingAuthorizationRequest(
            decision = decision,
            confirmed = true,
            policyVersion =
                UpdateVoiceCollectionTrainingAuthorizationRequest.PolicyVersion
                    .VOICE_MODEL_TRAINING_V1,
            expectedVersion = expectedVersion,
        )
        return executeAuthenticated(
            request = {
                api.updateVoiceCollectionTrainingAuthorization(
                    sampleId, idempotencyKey, requestBody,
                )
            },
            body = { it.data },
        )
    }

    private suspend fun <T, R> executeAuthenticated(
        request: suspend () -> Response<T>,
        body: (T) -> R,
    ): R {
        var response = executeRequest(request)
        if (response.code() == 401) {
            authSessionRepository.refresh()
            response = executeRequest(request)
        }
        val responseBody = response.body()?.takeIf { response.isSuccessful }
            ?: throw AuthApiException(response.code(), failureMessage(response.code()))
        return body(responseBody)
    }

    private suspend fun <T> executeRequest(request: suspend () -> Response<T>): Response<T> =
        try {
            request()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw AuthApiException(0, "测试语音服务暂时无法连接，请稍后重试")
        }

    private fun failureMessage(statusCode: Int): String = when (statusCode) {
        403 -> "测试采集或训练授权已失效，请重新确认"
        409 -> "采集状态已变化，请刷新后重试"
        else -> "测试语音采集没有完成，请稍后重试"
    }

    companion object {
        const val POLICY_VERSION = "test-voice-collection-v1"
        const val DIALECT_CODE = "zh-Hans-CN-x-wugang"
        const val TRAINING_POLICY_VERSION = "voice-model-training-v1"
        const val REVIEW_POLICY_VERSION = "voice-sample-review-v1"
    }
}
