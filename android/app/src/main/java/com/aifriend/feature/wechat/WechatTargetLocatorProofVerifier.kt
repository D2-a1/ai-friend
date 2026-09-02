package com.aifriend.feature.wechat

import com.aifriend.BuildConfig
import com.aifriend.contract.model.WechatActionPlan
import com.aifriend.contract.model.WechatTargetLocatorProof
import com.google.crypto.tink.subtle.Ed25519Verify
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.nio.CharBuffer
import java.security.MessageDigest
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** 只把通过固定公钥验签的当前计划证明提升为可信内存事实。 */
interface WechatTargetLocatorProofVerifier {
    fun verify(
        plan: WechatActionPlan,
        now: OffsetDateTime,
    ): VerifiedWechatTargetLocatorProof?
}

/** 正式公钥缺失、格式错误或验签失败时固定返回空。 */
@Singleton
class ConfiguredWechatTargetLocatorProofVerifier @Inject constructor() :
    WechatTargetLocatorProofVerifier {
    override fun verify(
        plan: WechatActionPlan,
        now: OffsetDateTime,
    ): VerifiedWechatTargetLocatorProof? = SignedWechatTargetLocatorProofVerifier.verify(
        plan = plan,
        now = now,
        trustedKeyId = BuildConfig.WECHAT_ACTION_PLAN_TRUSTED_KEY_ID,
        trustedPublicKeyBase64 = BuildConfig.WECHAT_ACTION_PLAN_TRUSTED_PUBLIC_KEY_BASE64,
    )
}

/** Java/Kotlin 共同使用的 V1 长度前缀规范字节编码。 */
object WechatTargetLocatorProofCanonicalizer {
    private const val FORMAT_MAGIC = "AI_FRIEND_WECHAT_LOCATOR_PROOF_V1"

    fun canonicalBytes(plan: WechatActionPlan): ByteArray {
        val proof = plan.targetLocatorProof
        val bytes = ByteArrayOutputStream(512)
        DataOutputStream(bytes).use { output ->
            output.writeString(FORMAT_MAGIC)
            output.writeString(proof.proofVersion.value)
            output.writeString(proof.keyId)
            output.writeString(plan.planId)
            output.writeString(plan.action.value)
            output.writeString(plan.contactId)
            output.writeNullableString(plan.audioObjectId)
            output.writeString(plan.summaryHash)
            output.writeString(plan.minimumRuleVersion)
            output.writeLong(proof.contactVersion)
            output.writeString(proof.wechatVersion)
            output.writeString(proof.locatorVersion)
            output.writeString(proof.salt)
            output.writeString(proof.targetLocatorSha256)
            output.writeLong(proof.issuedAt.toInstant().toEpochMilli())
            output.writeLong(proof.expiresAt.toInstant().toEpochMilli())
        }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.writeString(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        if (value == null) {
            writeInt(-1)
        } else {
            writeString(value)
        }
    }
}

/** 纯 JVM Ed25519 验签器；不访问网络、文件、无障碍节点或持久化存储。 */
object SignedWechatTargetLocatorProofVerifier {
    private val keyIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,59}")
    private val saltPattern = Regex("[0-9a-f]{32}")
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val signaturePattern = Regex("[A-Za-z0-9_-]{86}")
    private val tokenPattern = Regex("[^\\s]{1,100}")
    private val wechatIdPattern = Regex("[A-Za-z][A-Za-z0-9_-]{5,63}")
    private val maximumProofLifetime = Duration.ofSeconds(30)
    private val futureClockTolerance = Duration.ofSeconds(2)

    fun verify(
        plan: WechatActionPlan,
        now: OffsetDateTime,
        trustedKeyId: String,
        trustedPublicKeyBase64: String,
    ): VerifiedWechatTargetLocatorProof? = runCatching {
        val proof = plan.targetLocatorProof
        require(trustedKeyId.matches(keyIdPattern) && trustedPublicKeyBase64.isNotBlank())
        require(proof.proofVersion == WechatTargetLocatorProof.ProofVersion.WECHAT_LOCATOR_PROOF_V1)
        require(proof.keyId == trustedKeyId && proof.keyId.matches(keyIdPattern))
        require(proof.contactVersion >= 0)
        require(proof.wechatVersion.matches(tokenPattern))
        require(proof.locatorVersion.matches(tokenPattern))
        require(proof.salt.matches(saltPattern))
        require(proof.targetLocatorSha256.matches(digestPattern))
        require(proof.signature.matches(signaturePattern))
        require(plan.targetSearchLocator.matches(wechatIdPattern))
        require(
            MessageDigest.isEqual(
                requireNotNull(
                    WechatTargetLocatorDigest.compute(plan.targetSearchLocator, proof.salt),
                ).encodeToByteArray(),
                proof.targetLocatorSha256.encodeToByteArray(),
            ),
        )
        require(proof.expiresAt.toInstant() == plan.expiresAt.toInstant())
        require(proof.expiresAt.isAfter(proof.issuedAt))
        require(!proof.expiresAt.isAfter(proof.issuedAt.plus(maximumProofLifetime)))
        require(!proof.issuedAt.isAfter(now.plus(futureClockTolerance)))
        require(proof.expiresAt.isAfter(now))

        val signature = Base64.getUrlDecoder().decode(proof.signature)
        require(signature.size == ED25519_SIGNATURE_BYTES)
        val publicKey = extractRawEd25519PublicKey(
            Base64.getDecoder().decode(trustedPublicKeyBase64),
        )
        Ed25519Verify(publicKey).verify(
            signature,
            WechatTargetLocatorProofCanonicalizer.canonicalBytes(plan),
        )
        VerifiedWechatTargetLocatorProof(
            planId = plan.planId,
            contactId = plan.contactId,
            contactVersion = proof.contactVersion,
            wechatVersion = proof.wechatVersion,
            locatorVersion = proof.locatorVersion,
            targetLocatorSha256 = proof.targetLocatorSha256,
            issuedAt = proof.issuedAt,
            expiresAt = proof.expiresAt,
            keyId = proof.keyId,
        )
    }.getOrNull()

    private fun extractRawEd25519PublicKey(encoded: ByteArray): ByteArray {
        val prefix = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
        require(encoded.size == prefix.size + Ed25519Verify.PUBLIC_KEY_LEN)
        require(encoded.copyOfRange(0, prefix.size).contentEquals(prefix))
        return encoded.copyOfRange(prefix.size, encoded.size)
    }

    private const val ED25519_SIGNATURE_BYTES = 64
}

/** 根据当前计划盐重算页面稳定定位摘要；失败时不返回候选摘要。 */
object WechatTargetLocatorDigest {
    private val saltPattern = Regex("[0-9a-f]{32}")
    private val domain = "ai-friend-wechat-locator-proof-v1"
        .toByteArray(StandardCharsets.US_ASCII)

    fun compute(stableLocator: String, saltHex: String): String? {
        val locatorChars = stableLocator.toCharArray()
        return try {
            compute(locatorChars, saltHex)
        } finally {
            locatorChars.fill('\u0000')
        }
    }

    fun compute(stableLocator: CharArray, saltHex: String): String? = runCatching {
        require(stableLocator.isNotEmpty() && stableLocator.any { !it.isWhitespace() })
        require(saltHex.matches(saltPattern))
        val salt = saltHex.hexToBytes()
        val encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(stableLocator))
        val locatorBytes = ByteArray(encoded.remaining())
        encoded.get(locatorBytes)
        if (encoded.hasArray()) encoded.array().fill(0)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(domain)
            digest.update(0)
            digest.update(salt)
            digest.update(locatorBytes)
            digest.digest().toHex()
        } finally {
            salt.fill(0)
            locatorBytes.fill(0)
        }
    }.getOrNull()

    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
