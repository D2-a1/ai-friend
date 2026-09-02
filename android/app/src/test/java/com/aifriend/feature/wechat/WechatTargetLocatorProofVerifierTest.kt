package com.aifriend.feature.wechat

import com.aifriend.contract.model.WechatActionPlan
import com.aifriend.contract.model.WechatActionType
import com.aifriend.contract.model.WechatTargetLocatorProof
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.OffsetDateTime
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 服务端 Java 与 Android 共用规范字节、盐化摘要及 Ed25519 验签测试。 */
class WechatTargetLocatorProofVerifierTest {
    private val now = OffsetDateTime.parse("2026-08-19T08:00:00Z")

    @Test
    fun rfc8032KeyVerifiesExactPlanAndRejectsAnyBoundFieldChange() {
        val unsigned = plan(signature = "A".repeat(86))
        val signature = Signature.getInstance("Ed25519").run {
            initSign(
                KeyFactory.getInstance("Ed25519").generatePrivate(
                    PKCS8EncodedKeySpec(PKCS8_PRIVATE_KEY),
                ),
            )
            update(WechatTargetLocatorProofCanonicalizer.canonicalBytes(unsigned))
            sign()
        }
        val signed = unsigned.copy(
            targetLocatorProof = unsigned.targetLocatorProof.copy(
                signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature),
            ),
        )

        val verified = SignedWechatTargetLocatorProofVerifier.verify(
            signed,
            now,
            KEY_ID,
            Base64.getEncoder().encodeToString(X509_PUBLIC_KEY),
        )

        assertNotNull(verified)
        assertEquals(LOCATOR_DIGEST, verified?.targetLocatorSha256)
        assertNull(
            SignedWechatTargetLocatorProofVerifier.verify(
                signed.copy(summaryHash = "f".repeat(64)),
                now,
                KEY_ID,
                Base64.getEncoder().encodeToString(X509_PUBLIC_KEY),
            ),
        )
        assertNull(
            SignedWechatTargetLocatorProofVerifier.verify(
                signed.copy(targetSearchLocator = "wxid_other999"),
                now,
                KEY_ID,
                Base64.getEncoder().encodeToString(X509_PUBLIC_KEY),
            ),
        )
        assertNull(
            SignedWechatTargetLocatorProofVerifier.verify(
                signed,
                now,
                "other-key",
                Base64.getEncoder().encodeToString(X509_PUBLIC_KEY),
            ),
        )
    }

    @Test
    fun missingTrustExpiredProofAndMalformedSignatureFailClosed() {
        val unsigned = plan(signature = "A".repeat(86))

        assertNull(SignedWechatTargetLocatorProofVerifier.verify(unsigned, now, "", ""))
        assertNull(
            SignedWechatTargetLocatorProofVerifier.verify(
                unsigned.copy(
                    expiresAt = now,
                    targetLocatorProof = unsigned.targetLocatorProof.copy(expiresAt = now),
                ),
                now,
                KEY_ID,
                Base64.getEncoder().encodeToString(X509_PUBLIC_KEY),
            ),
        )
        assertNull(
            SignedWechatTargetLocatorProofVerifier.verify(
                unsigned.copy(
                    targetLocatorProof = unsigned.targetLocatorProof.copy(signature = "bad"),
                ),
                now,
                KEY_ID,
                Base64.getEncoder().encodeToString(X509_PUBLIC_KEY),
            ),
        )
    }

    @Test
    fun locatorDigestMatchesCrossPlatformGoldenVector() {
        assertEquals(
            LOCATOR_DIGEST,
            WechatTargetLocatorDigest.compute("wxid_demo123", SALT),
        )
        val canonicalHash = MessageDigest.getInstance("SHA-256")
            .digest(WechatTargetLocatorProofCanonicalizer.canonicalBytes(plan("A".repeat(86))))
            .joinToString("") { "%02x".format(it) }
        assertEquals(CANONICAL_SHA256, canonicalHash)
    }

    private fun plan(signature: String): WechatActionPlan = WechatActionPlan(
        planId = "wp_test",
        action = WechatActionType.SEND_AUDIO_AND_TEXT,
        contactId = "ct_test",
        summaryHash = "a".repeat(64),
        minimumRuleVersion = "rule-v1",
        expiresAt = now.plusSeconds(30),
        targetSearchLocator = "wxid_demo123",
        targetLocatorProof = WechatTargetLocatorProof(
            proofVersion = WechatTargetLocatorProof.ProofVersion.WECHAT_LOCATOR_PROOF_V1,
            keyId = KEY_ID,
            contactVersion = 7,
            wechatVersion = "8.0.56",
            locatorVersion = "locator-v1",
            salt = SALT,
            targetLocatorSha256 = LOCATOR_DIGEST,
            issuedAt = now,
            expiresAt = now.plusSeconds(30),
            signature = signature,
        ),
        audioObjectId = "ao_test",
    )

    private companion object {
        const val KEY_ID = "test-rfc8032"
        const val SALT = "000102030405060708090a0b0c0d0e0f"
        const val LOCATOR_DIGEST =
            "54c65a40a71d8a383e09fb62689b25dd6a570c7d7997971fc738409e47ac32ed"
        const val CANONICAL_SHA256 =
            "91621bc28ed3759eceb409e439ccbeb86375efe73fe6aa450e7f7e2a0293bedf"
        val PKCS8_PRIVATE_KEY: ByteArray = (
            "302e020100300506032b657004220420" +
                "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
            ).hexToBytes()
        val X509_PUBLIC_KEY: ByteArray = (
            "302a300506032b6570032100" +
                "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
            ).hexToBytes()

        private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
