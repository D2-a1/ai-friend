package com.aifriend.feature.collection

import com.aifriend.contract.api.VoiceCollectionApi
import com.aifriend.contract.model.CreateVoiceCollectionSampleRequest
import com.aifriend.contract.model.DeleteVoiceCollectionSampleRequest
import com.aifriend.contract.model.UpdateVoiceCollectionTrainingAuthorizationRequest
import com.aifriend.contract.model.VoiceCollectionDeletionResponse
import com.aifriend.contract.model.VoiceCollectionSampleListResponse
import com.aifriend.contract.model.VoiceCollectionSampleResponse
import com.aifriend.contract.model.VoiceCollectionTrainingAuthorizationResponse
import com.aifriend.feature.auth.AuthApiException
import com.aifriend.feature.auth.AuthSession
import com.aifriend.feature.auth.AuthSessionRepository
import com.aifriend.feature.auth.WechatLoginDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response

/** 测试语音采集网络仓库错误边界测试。 */
class VoiceCollectionRepositoryTest {

    @Test
    fun `technical client failure is converted to Chinese message`() = runTest {
        val repository = VoiceCollectionRepository(ThrowingApi(), FakeAuthSessionRepository())

        try {
            repository.delete("vs_0123456789abcdef0123456789abcdef", 0L)
            fail("应拒绝未发出的删除请求")
        } catch (exception: AuthApiException) {
            assertEquals("测试语音服务暂时无法连接，请稍后重试", exception.message)
        }
    }

    private class ThrowingApi : VoiceCollectionApi {
        override suspend fun deleteVoiceCollectionSample(
            sampleId: String,
            idempotencyKey: String,
            deleteVoiceCollectionSampleRequest: DeleteVoiceCollectionSampleRequest,
        ): Response<VoiceCollectionDeletionResponse> =
            throw IllegalArgumentException("Non-body HTTP method cannot contain @Body")

        override suspend fun createVoiceCollectionSample(
            idempotencyKey: String,
            createVoiceCollectionSampleRequest: CreateVoiceCollectionSampleRequest,
        ): Response<VoiceCollectionSampleResponse> = error("未使用")

        override suspend fun listVoiceCollectionSamples(): Response<VoiceCollectionSampleListResponse> =
            error("未使用")

        override suspend fun updateVoiceCollectionTrainingAuthorization(
            sampleId: String,
            idempotencyKey: String,
            updateVoiceCollectionTrainingAuthorizationRequest:
                UpdateVoiceCollectionTrainingAuthorizationRequest,
        ): Response<VoiceCollectionTrainingAuthorizationResponse> = error("未使用")
    }

    private class FakeAuthSessionRepository : AuthSessionRepository {
        override val session: StateFlow<AuthSession?> = MutableStateFlow(null)

        override suspend fun restore(): AuthSession? = null

        override suspend fun loginWithWechatCode(
            code: String,
            device: WechatLoginDevice,
        ): AuthSession = error("未使用")

        override suspend fun refresh(): AuthSession = error("未使用")

        override suspend fun clearLocalSession() = Unit
    }
}
