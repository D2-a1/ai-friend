package com.aifriend.feature.audio

import com.aifriend.contract.api.AudioApi
import com.aifriend.contract.model.AudioPurpose
import com.aifriend.contract.model.AudioUploadTicket
import com.aifriend.contract.model.AudioUploadTicketResponse
import com.aifriend.contract.model.CreateAudioUploadTicketRequest
import com.aifriend.feature.auth.AuthSession
import com.aifriend.feature.auth.AuthSessionRepository
import com.aifriend.feature.auth.WechatLoginDevice
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response as RetrofitResponse

class DefaultAudioUploadRepositoryTest {

    @Test
    fun ticket401RefreshesOnceAndBinaryUploadNeverCarriesBearerToken() = runTest {
        val audioContent = byteArrayOf(1, 2, 3, 4)
        val api = FakeAudioApi(firstResponseUnauthorized = true)
        val auth = FakeAuthSessionRepository()
        val uploadInterceptor = CapturingUploadInterceptor(204)
        val repository = DefaultAudioUploadRepository(
            api,
            auth,
            OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .addInterceptor(uploadInterceptor)
                .build(),
        )

        val audioObjectId = repository.upload(
            AudioPurpose.TASK,
            CreateAudioUploadTicketRequest.MediaType.AUDIO_SLASH_MP4,
            2_000,
            audioContent,
        )

        assertEquals("au_0123456789abcdef0123456789abcdef", audioObjectId)
        assertEquals(1, auth.refreshCount)
        assertEquals(2, api.calls.size)
        assertEquals(api.calls[0], api.calls[1])
        assertEquals(audioContent.sha256Hex(), api.calls.first().request.sha256)
        assertEquals(1, uploadInterceptor.requestCount)
        assertNull(uploadInterceptor.authorizationHeader)
        assertEquals("upload-secret", uploadInterceptor.uploadTokenHeader)
        assertArrayEquals(audioContent, uploadInterceptor.body)
    }

    @Test
    fun failedBinaryUploadIsNotAutomaticallyRetried() = runTest {
        val api = FakeAudioApi(firstResponseUnauthorized = false)
        val interceptor = CapturingUploadInterceptor(500)
        val repository = DefaultAudioUploadRepository(
            api,
            FakeAuthSessionRepository(),
            OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .addInterceptor(interceptor)
                .build(),
        )

        var exception: AudioUploadException? = null
        try {
            repository.upload(
                AudioPurpose.ALIAS_ENROLLMENT,
                CreateAudioUploadTicketRequest.MediaType.AUDIO_SLASH_AAC,
                1_000,
                byteArrayOf(9, 8, 7),
            )
        } catch (caught: AudioUploadException) {
            exception = caught
        }

        assertNotNull(exception)
        assertEquals(500, exception?.statusCode)
        assertEquals(1, interceptor.requestCount)
    }

    @Test
    fun forbiddenCredentialHeaderIsRejectedBeforeNetworkCall() = runTest {
        val api = FakeAudioApi(
            firstResponseUnauthorized = false,
            requiredHeaders = mapOf("Authorization" to "Bearer must-not-be-used"),
        )
        val interceptor = CapturingUploadInterceptor(204)
        val repository = DefaultAudioUploadRepository(
            api,
            FakeAuthSessionRepository(),
            OkHttpClient.Builder().addInterceptor(interceptor).build(),
        )

        var exception: AudioUploadException? = null
        try {
            repository.upload(
                AudioPurpose.TASK,
                CreateAudioUploadTicketRequest.MediaType.AUDIO_SLASH_MP4,
                1_000,
                byteArrayOf(1),
            )
        } catch (caught: AudioUploadException) {
            exception = caught
        }

        assertNotNull(exception)
        assertEquals(0, interceptor.requestCount)
    }

    @Test
    fun networkFailuresAreClassifiedWithoutExposingSensitiveDetails() {
        val repository = DefaultAudioUploadRepository(
            FakeAudioApi(firstResponseUnauthorized = false),
            FakeAuthSessionRepository(),
            OkHttpClient(),
        )

        assertEquals(
            "无法解析音频存储地址，音频没有上传",
            repository.uploadNetworkFailureMessage(
                UnknownHostException("secret-host"),
                "secret-host",
            ),
        )
        assertEquals(
            "连接音频存储超时，音频没有上传",
            repository.uploadNetworkFailureMessage(
                SocketTimeoutException("secret-url"),
                "secret-host",
            ),
        )
        assertEquals(
            "音频存储安全连接失败，音频没有上传",
            repository.uploadNetworkFailureMessage(
                SSLHandshakeException("secret-certificate"),
                "secret-host",
            ),
        )
        assertEquals(
            "服务器返回了本地开发上传地址，音频没有上传",
            repository.uploadNetworkFailureMessage(
                ConnectException("secret-address"),
                "10.0.2.2",
            ),
        )
        assertEquals(
            "无法连接阿里云音频存储公网地址，音频没有上传",
            repository.uploadNetworkFailureMessage(
                ConnectException("secret-address"),
                "private-bucket.oss-cn-guangzhou.aliyuncs.com",
            ),
        )
        assertEquals(
            "无法连接音频存储服务，音频没有上传",
            repository.uploadNetworkFailureMessage(
                ConnectException("secret-address"),
                "secret-host",
            ),
        )
        assertEquals(
            "音频存储连接中断，音频没有上传",
            repository.uploadNetworkFailureMessage(
                SocketException("secret-socket"),
                "secret-host",
            ),
        )
        assertEquals(
            "网络异常，音频没有上传",
            repository.uploadNetworkFailureMessage(
                java.io.IOException("secret-detail"),
                "secret-host",
            ),
        )
    }

    private data class TicketCall(
        val idempotencyKey: String,
        val request: CreateAudioUploadTicketRequest,
    )

    private class FakeAudioApi(
        private val firstResponseUnauthorized: Boolean,
        private val requiredHeaders: Map<String, String> = mapOf(
            "X-Audio-Upload-Token" to "upload-secret",
            "Content-Type" to "audio/mp4",
        ),
    ) : AudioApi {
        val calls = mutableListOf<TicketCall>()

        override suspend fun createAudioUploadTicket(
            idempotencyKey: String,
            createAudioUploadTicketRequest: CreateAudioUploadTicketRequest,
        ): RetrofitResponse<AudioUploadTicketResponse> {
            calls += TicketCall(idempotencyKey, createAudioUploadTicketRequest)
            if (firstResponseUnauthorized && calls.size == 1) {
                return RetrofitResponse.error(
                    401,
                    "{}".toResponseBody(),
                )
            }
            val ticket = AudioUploadTicket(
                audioObjectId = "au_0123456789abcdef0123456789abcdef",
                uploadUrl = URI.create("https://upload.example.com/private-object"),
                method = AudioUploadTicket.Method.PUT,
                requiredHeaders = requiredHeaders,
                expiresAt = OffsetDateTime.parse("2026-08-10T02:10:00Z"),
                objectKey = "temporary/private-object",
            )
            return RetrofitResponse.success(
                AudioUploadTicketResponse(
                    code = AudioUploadTicketResponse.Code.OK,
                    message = "success",
                    data = ticket,
                    traceId = "trace",
                ),
            )
        }
    }

    private class CapturingUploadInterceptor(
        private val responseCode: Int,
    ) : Interceptor {
        var requestCount = 0
        var authorizationHeader: String? = null
        var uploadTokenHeader: String? = null
        var body: ByteArray = byteArrayOf()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requestCount++
            authorizationHeader = request.header("Authorization")
            uploadTokenHeader = request.header("X-Audio-Upload-Token")
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            body = buffer.readByteArray()
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(responseCode)
                .message("test")
                .body(byteArrayOf().toResponseBody())
                .build()
        }
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
                accessTokenExpiresAt = Instant.parse("2026-08-10T03:00:00Z"),
                refreshTokenExpiresAt = Instant.parse("2026-09-10T02:00:00Z"),
            )
        }

        override suspend fun clearLocalSession() = Unit
    }

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
