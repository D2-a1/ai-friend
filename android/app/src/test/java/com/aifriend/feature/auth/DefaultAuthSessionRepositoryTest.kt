package com.aifriend.feature.auth

import com.aifriend.contract.api.AuthApi
import com.aifriend.contract.model.CurrentUser
import com.aifriend.contract.model.RefreshTokenRequest
import com.aifriend.contract.model.TokenPair
import com.aifriend.contract.model.WechatSessionRequest
import com.aifriend.contract.model.WechatSessionResponse
import com.aifriend.core.security.SecureStorePort
import com.aifriend.core.security.SessionCredentialStore
import com.aifriend.core.network.ApiErrorCodeReader
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import retrofit2.Response

class DefaultAuthSessionRepositoryTest {

    @Test
    fun loginRefreshAndRestoreKeepTokensOutsideUiModel() = runTest {
        val secureStore = InMemorySecureStore()
        val credentialStore = SessionCredentialStore(secureStore, Json)
        val repository = DefaultAuthSessionRepository(
            FakeAuthApi(), credentialStore, ApiErrorCodeReader(Json), FakeDeviceIdentity(),
        )

        val loggedIn = repository.loginWithWechatCode(
            "local_owner.12345678",
            WechatLoginDevice("12", "0.0.1-debug", "emulator"),
        )
        assertEquals(1L, repository.loginEpoch)
        val refreshed = repository.refresh()
        assertEquals(1L, repository.loginEpoch)
        val restoredRepository = DefaultAuthSessionRepository(
            FakeAuthApi(),
            SessionCredentialStore(secureStore, Json),
            ApiErrorCodeReader(Json),
            FakeDeviceIdentity(),
        )
        val restored = restoredRepository.restore()
        assertEquals(1L, restoredRepository.loginEpoch)

        assertEquals("us_0123456789abcdef0123456789abcdef", loggedIn.userId)
        assertEquals(loggedIn.userId, refreshed.userId)
        assertNotNull(restored)
        assertEquals(loggedIn.userId, restored?.userId)
        assertEquals("refresh-2", secureStore.latestSessionText()?.let { Json.parseToJsonElement(it) }
            ?.jsonObject?.get("refreshToken")?.toString()?.trim('"'))
        repository.clearLocalSession()
        assertEquals(2L, repository.loginEpoch)
    }

    @Test
    fun loginRecognizesStableAccountClosureCodeInsteadOfAnyConflict() = runTest {
        val errorJson = """{
            "code":"ACCOUNT_CLOSURE_ACCEPTED",
            "message":"注销申请已经受理",
            "traceId":"trace"
        }""".trimIndent()
        val repository = DefaultAuthSessionRepository(
            ClosureAcceptedAuthApi(errorJson),
            SessionCredentialStore(InMemorySecureStore(), Json),
            ApiErrorCodeReader(Json),
            FakeDeviceIdentity(),
        )

        val failure = runCatching {
            repository.loginWithWechatCode(
                "local_owner.12345678",
                WechatLoginDevice("12", "0.0.1-debug", "emulator"),
            )
        }.exceptionOrNull()

        org.junit.Assert.assertTrue(failure is AccountClosureAcceptedException)
    }

    @Test
    fun refreshClearsSessionWhenCurrentDeviceWasRemovedFromAllowlist() = runTest {
        val errorJson = """{
            "code":"DEVICE_NOT_ALLOWED",
            "message":"这台手机尚未放行",
            "traceId":"trace"
        }""".trimIndent()
        val repository = DefaultAuthSessionRepository(
            DeviceRejectedAuthApi(errorJson),
            SessionCredentialStore(InMemorySecureStore(), Json),
            ApiErrorCodeReader(Json),
            FakeDeviceIdentity(),
        )
        repository.loginWithWechatCode(
            "local_owner.12345678",
            WechatLoginDevice("12", "0.0.1-debug", "emulator"),
        )

        val failure = runCatching { repository.refresh() }.exceptionOrNull()

        org.junit.Assert.assertTrue(failure is AuthApiException)
        org.junit.Assert.assertTrue(failure?.message?.contains("这台手机尚未放行") == true)
        assertEquals(null, repository.session.value)
    }

    private class FakeAuthApi : AuthApi {
        override suspend fun createWechatSession(
            wechatSessionRequest: WechatSessionRequest,
        ): Response<WechatSessionResponse> {
            assertEquals("public-key", wechatSessionRequest.device.publicKeySpkiBase64)
            assertEquals("login-proof", wechatSessionRequest.device.proofBase64)
            return Response.success(response("access-1", "refresh-1"))
        }

        override suspend fun refreshAccessToken(
            refreshTokenRequest: RefreshTokenRequest,
        ): Response<WechatSessionResponse> {
            assertEquals("public-key", refreshTokenRequest.publicKeySpkiBase64)
            assertEquals("refresh-proof", refreshTokenRequest.proofBase64)
            return Response.success(response("access-2", "refresh-2"))
        }

        fun response(accessToken: String, refreshToken: String): WechatSessionResponse {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            return WechatSessionResponse(
                code = WechatSessionResponse.Code.OK,
                message = "success",
                data = TokenPair(
                    accessToken = accessToken,
                    accessTokenExpiresAt = now.plusMinutes(15),
                    refreshToken = refreshToken,
                    refreshTokenExpiresAt = now.plusDays(30),
                    user = CurrentUser(
                        id = "us_0123456789abcdef0123456789abcdef",
                        status = CurrentUser.Status.ACTIVE,
                    ),
                ),
                traceId = "trace",
            )
        }
    }

    private class ClosureAcceptedAuthApi(private val errorJson: String) : AuthApi {
        override suspend fun createWechatSession(
            wechatSessionRequest: WechatSessionRequest,
        ): Response<WechatSessionResponse> = Response.error(
            409,
            errorJson.toResponseBody("application/json".toMediaType()),
        )

        override suspend fun refreshAccessToken(
            refreshTokenRequest: RefreshTokenRequest,
        ): Response<WechatSessionResponse> = error("unused")
    }

    private class DeviceRejectedAuthApi(private val errorJson: String) : AuthApi {
        override suspend fun createWechatSession(
            wechatSessionRequest: WechatSessionRequest,
        ): Response<WechatSessionResponse> = Response.success(
            FakeAuthApi().run { response("access-1", "refresh-1") },
        )

        override suspend fun refreshAccessToken(
            refreshTokenRequest: RefreshTokenRequest,
        ): Response<WechatSessionResponse> = Response.error(
            403,
            errorJson.toResponseBody("application/json".toMediaType()),
        )
    }

    private class FakeDeviceIdentity : DeviceIdentityPort {
        override fun fingerprint(): String = "a".repeat(64)

        override fun publicKeySpkiBase64(): String = "public-key"

        override fun signLogin(code: String): String = "login-proof"

        override fun signRefresh(refreshToken: String): String = "refresh-proof"
    }

    private class InMemorySecureStore : SecureStorePort {
        private val values = mutableMapOf<String, ByteArray>()

        override suspend fun put(key: String, value: ByteArray) {
            values[key] = value.copyOf()
        }

        override suspend fun get(key: String): ByteArray? = values[key]?.copyOf()

        override suspend fun remove(key: String) {
            values.remove(key)
        }

        override suspend fun wipe() {
            values.clear()
        }

        fun latestSessionText(): String? = values.values.firstOrNull()?.decodeToString()
    }
}
