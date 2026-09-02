package com.aifriend.feature.auth

import com.aifriend.contract.api.AuthApi
import com.aifriend.contract.model.DeviceContext
import com.aifriend.contract.model.RefreshTokenRequest
import com.aifriend.contract.model.TokenPair
import com.aifriend.contract.model.WechatSessionRequest
import com.aifriend.core.security.SessionCredentialStore
import com.aifriend.core.security.StoredAuthSession
import com.aifriend.core.network.ApiErrorCodeReader
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 基于 OpenAPI 生成 AuthApi 和 Keystore 会话密文的登录仓库。
 *
 * @author codex
 * @since 2026-08-04
 */
@Singleton
class DefaultAuthSessionRepository @Inject constructor(
    private val authApi: AuthApi,
    private val credentialStore: SessionCredentialStore,
    private val errorCodeReader: ApiErrorCodeReader,
    private val deviceIdentity: DeviceIdentityPort,
) : AuthSessionRepository {

    private val refreshMutex = Mutex()
    private val mutableSession = MutableStateFlow<AuthSession?>(null)
    override val session: StateFlow<AuthSession?> = mutableSession.asStateFlow()

    override suspend fun restore(): AuthSession? {
        val restored = credentialStore.restore()?.toDomain()
        mutableSession.value = restored
        return restored
    }

    override suspend fun loginWithWechatCode(code: String, device: WechatLoginDevice): AuthSession {
        val response = authApi.createWechatSession(
            WechatSessionRequest(
                code = code,
                device = DeviceContext(
                    platform = DeviceContext.Platform.ANDROID,
                    osVersion = device.osVersion,
                    appVersion = device.appVersion,
                    deviceModel = device.deviceModel,
                    publicKeySpkiBase64 = deviceIdentity.publicKeySpkiBase64(),
                    proofBase64 = deviceIdentity.signLogin(code),
                ),
            ),
        )
        val tokenPair = response.body()?.data?.takeIf { response.isSuccessful }
        if (tokenPair == null) {
            if (errorCodeReader.read(response) == ACCOUNT_CLOSURE_ACCEPTED) {
                throw AccountClosureAcceptedException()
            }
            throw AuthApiException(response.code(), loginFailureMessage(response.code()))
        }
        return persist(tokenPair)
    }

    override suspend fun refresh(): AuthSession = refreshMutex.withLock {
        val stored = credentialStore.session.value
            ?: credentialStore.restore()
            ?: throw AuthApiException(401, "登录已失效，请重新登录")
        val response = authApi.refreshAccessToken(
            RefreshTokenRequest(
                refreshToken = stored.refreshToken,
                publicKeySpkiBase64 = deviceIdentity.publicKeySpkiBase64(),
                proofBase64 = deviceIdentity.signRefresh(stored.refreshToken),
            ),
        )
        val tokenPair = response.body()?.data?.takeIf { response.isSuccessful }
        if (tokenPair == null) {
            val errorCode = errorCodeReader.read(response)
            val accountClosureAccepted = errorCode == ACCOUNT_CLOSURE_ACCEPTED
            val deviceNotAllowed = errorCode == DEVICE_NOT_ALLOWED
            if (response.code() == 401 || response.code() == 409 || deviceNotAllowed) {
                clearLocalSession()
            }
            if (accountClosureAccepted) {
                throw AccountClosureAcceptedException()
            }
            if (deviceNotAllowed) {
                throw AuthApiException(
                    response.code(),
                    "这台手机尚未放行，请把本机设备编号加入服务器白名单",
                )
            }
            throw AuthApiException(response.code(), "登录已失效，请重新登录")
        }
        persist(tokenPair)
    }

    override suspend fun clearLocalSession() {
        credentialStore.clear()
        mutableSession.value = null
    }

    private suspend fun persist(tokenPair: TokenPair): AuthSession {
        val stored = StoredAuthSession(
            accessToken = tokenPair.accessToken,
            accessTokenExpiresAt = tokenPair.accessTokenExpiresAt.toInstant().toString(),
            refreshToken = tokenPair.refreshToken,
            refreshTokenExpiresAt = tokenPair.refreshTokenExpiresAt.toInstant().toString(),
            userId = tokenPair.user.id,
            userStatus = tokenPair.user.status.value,
            displayName = tokenPair.user.displayName,
        )
        credentialStore.save(stored)
        return stored.toDomain().also { mutableSession.value = it }
    }

    private fun StoredAuthSession.toDomain(): AuthSession = AuthSession(
        userId = userId,
        userStatus = userStatus,
        displayName = displayName,
        accessTokenExpiresAt = java.time.Instant.parse(accessTokenExpiresAt),
        refreshTokenExpiresAt = java.time.Instant.parse(refreshTokenExpiresAt),
    )

    private fun loginFailureMessage(status: Int): String = when (status) {
        403 -> "这台手机尚未放行，请把本机设备编号加入服务器白名单"
        409 -> "登录状态冲突，请稍后重试"
        429 -> "操作太频繁，请稍后再试"
        else -> "微信登录失败，请稍后重试"
    }

    private companion object {
        const val ACCOUNT_CLOSURE_ACCEPTED = "ACCOUNT_CLOSURE_ACCEPTED"
        const val DEVICE_NOT_ALLOWED = "DEVICE_NOT_ALLOWED"
    }
}
