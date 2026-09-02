package com.aifriend.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 使用 Android Keystore AES-GCM 保护 App 私有 SharedPreferences 中的会话数据。
 *
 * 明文 token 只在调用内存中短暂存在，不写日志、不进入备份。
 *
 * @author codex
 * @since 2026-08-04
 */
@Singleton
class AndroidKeystoreSecureStore @Inject constructor(
    @ApplicationContext context: Context,
) : SecureStorePort {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    override suspend fun put(key: String, value: ByteArray) = withContext(Dispatchers.IO) {
        validateKey(key)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(value)
        val payload = ByteArray(cipher.iv.size + encrypted.size)
        cipher.iv.copyInto(payload)
        encrypted.copyInto(payload, destinationOffset = cipher.iv.size)
        check(preferences.edit().putString(key, Base64.encodeToString(payload, Base64.NO_WRAP)).commit()) {
            "安全存储写入失败"
        }
    }

    override suspend fun get(key: String): ByteArray? = withContext(Dispatchers.IO) {
        validateKey(key)
        val encoded = preferences.getString(key, null) ?: return@withContext null
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > GCM_IV_BYTES) { "安全存储数据损坏" }
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.doFinal(encrypted)
    }

    override suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        validateKey(key)
        check(preferences.edit().remove(key).commit()) { "安全存储删除失败" }
    }

    override suspend fun wipe() = withContext(Dispatchers.IO) {
        check(preferences.edit().clear().commit()) { "安全存储清理失败" }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) {
            return existing
        }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val specification = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build()
        keyGenerator.init(specification)
        return keyGenerator.generateKey()
    }

    private fun validateKey(key: String) {
        require(key.matches(Regex("[a-z0-9_.-]{1,80}"))) { "安全存储键名不合法" }
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "ai_friend_session_key_v1"
        const val PREFERENCES_NAME = "ai_friend_secure_store"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_IV_BYTES = 12
    }
}
