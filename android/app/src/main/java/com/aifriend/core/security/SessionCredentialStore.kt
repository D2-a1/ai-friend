package com.aifriend.core.security

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 会话密文持久化与内存访问令牌状态管理器。
 *
 * @author codex
 * @since 2026-08-04
 */
@Singleton
class SessionCredentialStore @Inject constructor(
    private val secureStore: SecureStorePort,
    private val json: Json,
) : AccessTokenProvider {

    private val mutableSession = MutableStateFlow<StoredAuthSession?>(null)
    val session: StateFlow<StoredAuthSession?> = mutableSession.asStateFlow()

    suspend fun restore(): StoredAuthSession? {
        val restored = runCatching {
            val encoded = secureStore.get(SESSION_KEY) ?: return null
            json.decodeFromString<StoredAuthSession>(encoded.decodeToString())
        }.getOrElse {
            mutableSession.value = null
            secureStore.wipe()
            return null
        }
        if (!Instant.parse(restored.refreshTokenExpiresAt).isAfter(Instant.now())) {
            clear()
            return null
        }
        mutableSession.value = restored
        return restored
    }

    suspend fun save(session: StoredAuthSession) {
        secureStore.put(SESSION_KEY, json.encodeToString(session).encodeToByteArray())
        mutableSession.value = session
    }

    suspend fun clear() {
        mutableSession.value = null
        secureStore.remove(SESSION_KEY)
    }

    /** 清除全部会话密文并销毁专用 Keystore 密钥。 */
    suspend fun wipe() {
        mutableSession.value = null
        secureStore.wipe()
    }

    override fun currentAccessToken(): String? = mutableSession.value?.accessToken

    private companion object {
        const val SESSION_KEY = "auth.session.v1"
    }
}
