package com.aifriend.feature.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 使用 Android Keystore 生成并持有本机安装实例的不可导出设备私钥。
 *
 * 公钥和 SHA-256 指纹不是秘密；私钥只用于签署一次性登录 code 或轮换刷新令牌，
 * 不用于声纹、用户身份识别或业务内容签名。
 *
 * @author codex
 * @since 2026-08-29
 */
@Singleton
class AndroidDeviceIdentity @Inject constructor() : DeviceIdentityPort {

    private val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    /** 返回可填写到服务器白名单的 64 位小写公钥 SHA-256 指纹。 */
    override fun fingerprint(): String = sha256(publicKeyBytes()).toHex()

    /** 返回 Android Keystore X.509 SPKI 公钥的标准 Base64。 */
    override fun publicKeySpkiBase64(): String =
        Base64.encodeToString(publicKeyBytes(), Base64.NO_WRAP)

    /** 对登录一次性 code 生成绑定当前设备的 SHA256withECDSA 签名。 */
    override fun signLogin(code: String): String = sign(LOGIN_DOMAIN, code)

    /** 对一次性轮换刷新令牌生成绑定当前设备的 SHA256withECDSA 签名。 */
    override fun signRefresh(refreshToken: String): String = sign(REFRESH_DOMAIN, refreshToken)

    private fun sign(domain: String, oneTimeSecret: String): String {
        val privateKey = getOrCreateKeyPair().private
        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(privateKey)
        signer.update(canonical(domain, oneTimeSecret))
        return Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
    }

    private fun publicKeyBytes(): ByteArray = getOrCreateKeyPair().public.encoded

    private fun getOrCreateKeyPair(): java.security.KeyPair {
        val existingPrivateKey = keyStore.getKey(KEY_ALIAS, null) as? java.security.PrivateKey
        val existingCertificate = keyStore.getCertificate(KEY_ALIAS)
        if (existingPrivateKey != null && existingCertificate != null) {
            return java.security.KeyPair(existingCertificate.publicKey, existingPrivateKey)
        }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
        val specification = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE_NAME))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build()
        generator.initialize(specification)
        return generator.generateKeyPair()
    }

    private fun canonical(domain: String, oneTimeSecret: String): ByteArray {
        val domainBytes = domain.toByteArray(StandardCharsets.US_ASCII)
        val secretDigest = sha256(oneTimeSecret.toByteArray(StandardCharsets.UTF_8))
        return ByteArray(domainBytes.size + 1 + secretDigest.size).also { output ->
            domainBytes.copyInto(output)
            output[domainBytes.size] = 0
            secretDigest.copyInto(output, destinationOffset = domainBytes.size + 1)
        }
    }

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "ai_friend_device_identity_v1"
        const val CURVE_NAME = "secp256r1"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        const val LOGIN_DOMAIN = "ai-friend-device-login-v1"
        const val REFRESH_DOMAIN = "ai-friend-device-refresh-v1"
    }
}
