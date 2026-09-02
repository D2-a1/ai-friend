package com.aifriend.core.voice

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** 使用独立 Android Keystore 密钥保护 Room 中的发音内容模板。 */
interface VoiceTemplateCipher {
    fun encrypt(plainText: ByteArray, associatedData: ByteArray): ByteArray
    fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray
    fun destroyKey()
}

/**
 * 专用于语音模板的 AES-256-GCM 密码器。
 *
 * 密钥别名与登录会话完全分离，AAD 固定绑定 owner、模板 ID 和版本元数据，
 * 避免密文被复制到其他用户或模板记录后仍可解密。
 *
 * @author codex
 * @since 2026-08-13
 */
@Singleton
class AndroidKeystoreVoiceTemplateCipher @Inject constructor() : VoiceTemplateCipher {
    private val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    override fun encrypt(plainText: ByteArray, associatedData: ByteArray): ByteArray {
        require(plainText.isNotEmpty() && associatedData.isNotEmpty())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(associatedData)
        val encrypted = cipher.doFinal(plainText)
        return ByteArray(cipher.iv.size + encrypted.size).also { payload ->
            cipher.iv.copyInto(payload)
            encrypted.copyInto(payload, destinationOffset = cipher.iv.size)
            encrypted.fill(0)
        }
    }

    override fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray {
        require(payload.size > GCM_IV_BYTES + GCM_TAG_BYTES && associatedData.isNotEmpty())
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(associatedData)
            cipher.doFinal(encrypted)
        } finally {
            iv.fill(0)
            encrypted.fill(0)
        }
    }

    override fun destroyKey() {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "ai_friend_voice_template_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = 16
        const val GCM_IV_BYTES = 12
    }
}
