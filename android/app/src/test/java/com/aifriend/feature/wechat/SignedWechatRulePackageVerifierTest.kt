package com.aifriend.feature.wechat

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 签名微信规则包、能力矩阵和默认零页面快照边界测试。 */
class SignedWechatRulePackageVerifierTest {

    @Test
    fun validSignedMatrixSelectsExactlyCurrentCombinationWithoutPageAccess() {
        val fixture = signedFixture()

        val capability = SignedWechatRulePackageVerifier.verify(
            fixture.source,
            fixture.publicKey,
            TRUSTED_KEY_ID,
            runtimeFacts(),
        )
        val context = SignedWechatExecutionContextProvider(
            WechatRulePackageRegistry { capability },
            WechatPageObservationBroker(),
        ).current(placeholderPlan(), OffsetDateTime.parse("2026-08-19T08:00:00Z"))

        assertTrue(capability.signedRulesTrusted)
        assertTrue(capability.combinationApproved)
        assertEquals(RULE_VERSION, capability.ruleVersion)
        assertEquals(35, capability.androidSdkInt)
        assertNull(context.pageSnapshot)
        assertNull(context.targetLocatorProof)
    }

    @Test
    fun signedPageTypeMappingKeepsProfileAndDirectChatSignaturesSeparate() {
        val profileSignature = "d".repeat(64)
        val fixture = signedFixture(
            combination = combination().copy(
                allowedPageTypes = mapOf(
                    "SEND_AUDIO_AND_TEXT" to listOf("CONTACT_PROFILE", "DIRECT_CHAT"),
                ),
                allowedPageSignatures = mapOf(
                    "SEND_AUDIO_AND_TEXT" to listOf(profileSignature, PAGE_SIGNATURE),
                ),
                allowedPageSignaturesByType = mapOf(
                    "SEND_AUDIO_AND_TEXT" to mapOf(
                        "CONTACT_PROFILE" to listOf(profileSignature),
                        "DIRECT_CHAT" to listOf(PAGE_SIGNATURE),
                    ),
                ),
            ),
        )

        val capability = SignedWechatRulePackageVerifier.verify(
            fixture.source,
            fixture.publicKey,
            TRUSTED_KEY_ID,
            runtimeFacts(),
        )

        assertEquals(
            setOf(profileSignature),
            capability.pageSignatures(
                com.aifriend.contract.model.WechatActionType.SEND_AUDIO_AND_TEXT,
                WechatPageType.CONTACT_PROFILE,
            ),
        )
        assertEquals(
            setOf(PAGE_SIGNATURE),
            capability.pageSignatures(
                com.aifriend.contract.model.WechatActionType.SEND_AUDIO_AND_TEXT,
                WechatPageType.DIRECT_CHAT,
            ),
        )
    }

    @Test
    fun multiplePageTypesWithoutPageSpecificMappingAreRejected() {
        val fixture = signedFixture(
            combination = combination().copy(
                allowedPageTypes = mapOf(
                    "SEND_AUDIO_AND_TEXT" to listOf("CONTACT_PROFILE", "DIRECT_CHAT"),
                ),
            ),
        )

        assertFails {
            SignedWechatRulePackageVerifier.verify(
                fixture.source,
                fixture.publicKey,
                TRUSTED_KEY_ID,
                runtimeFacts(),
            )
        }
    }

    @Test
    fun manifestSignatureAndCapabilityHashTamperingFailClosed() {
        val signatureFixture = signedFixture()
        signatureFixture.files["manifest.sig"] = Base64.getEncoder().encode(ByteArray(64))
        assertFails {
            SignedWechatRulePackageVerifier.verify(
                signatureFixture.source,
                signatureFixture.publicKey,
                TRUSTED_KEY_ID,
                runtimeFacts(),
            )
        }

        val capabilityFixture = signedFixture()
        capabilityFixture.files[CAPABILITY_FILE] = "{}".encodeToByteArray()
        assertFails {
            SignedWechatRulePackageVerifier.verify(
                capabilityFixture.source,
                capabilityFixture.publicKey,
                TRUSTED_KEY_ID,
                runtimeFacts(),
            )
        }
    }

    @Test
    fun unknownManifestFieldAndTraversalReferenceAreRejectedEvenWhenSigned() {
        val unknownField = signedFixture(extraManifestField = true)
        assertFails {
            SignedWechatRulePackageVerifier.verify(
                unknownField.source,
                unknownField.publicKey,
                TRUSTED_KEY_ID,
                runtimeFacts(),
            )
        }

        val traversal = signedFixture(capabilityFile = "../capabilities.json")
        assertFails {
            SignedWechatRulePackageVerifier.verify(
                traversal.source,
                traversal.publicKey,
                TRUSTED_KEY_ID,
                runtimeFacts(),
            )
        }
    }

    @Test
    fun deviceAppWechatAndRuleActionMismatchNeverUseNearbyCombination() {
        val fixture = signedFixture()
        assertFails {
            SignedWechatRulePackageVerifier.verify(
                fixture.source,
                fixture.publicKey,
                TRUSTED_KEY_ID,
                runtimeFacts().copy(deviceModel = "other-model"),
            )
        }

        val invalidAction = signedFixture(
            combination = combination().copy(
                allowedActions = listOf("DELETE_CHAT"),
                allowedPageTypes = mapOf("DELETE_CHAT" to listOf("DIRECT_CHAT")),
                allowedPageSignatures = mapOf("DELETE_CHAT" to listOf(PAGE_SIGNATURE)),
            ),
        )
        assertFails {
            SignedWechatRulePackageVerifier.verify(
                invalidAction.source,
                invalidAction.publicKey,
                TRUSTED_KEY_ID,
                runtimeFacts(),
            )
        }
    }

    @Test
    fun missingRulePackageRemainsDisabledAndDiagnosticsHideHashes() {
        val context = SignedWechatExecutionContextProvider(
            WechatRulePackageRegistry { null },
            WechatPageObservationBroker(),
        ).current(placeholderPlan(), OffsetDateTime.parse("2026-08-19T08:00:00Z"))

        assertFalse(context.capability.remotelyEnabled)
        assertFalse(context.capability.toString().contains("c".repeat(64)))
        assertNull(context.pageSnapshot)
    }

    private fun signedFixture(
        combination: WechatCapabilityCombination = combination(),
        capabilityFile: String = CAPABILITY_FILE,
        extraManifestField: Boolean = false,
    ): Fixture {
        val json = Json { encodeDefaults = true; explicitNulls = true }
        val matrix = WechatCapabilityMatrix(
            schemaVersion = SCHEMA_VERSION,
            combinations = listOf(combination),
        )
        val capabilityBytes = json.encodeToString(matrix).encodeToByteArray()
        val manifest = WechatRulePackageManifest(
            packageVersion = "test-rules-1",
            schemaVersion = SCHEMA_VERSION,
            capabilityFile = capabilityFile,
            capabilitySha256 = sha256(capabilityBytes),
            signatureKeyId = TRUSTED_KEY_ID,
            issuedAt = Instant.parse("2026-08-19T00:00:00Z").toString(),
        )
        var manifestText = json.encodeToString(manifest)
        if (extraManifestField) {
            manifestText = manifestText.dropLast(1) + ",\"unexpected\":true}"
        }
        val manifestBytes = manifestText.encodeToByteArray()
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(manifestBytes)
        val files = mutableMapOf(
            "manifest.json" to manifestBytes,
            "manifest.sig" to Base64.getEncoder().encode(signer.sign()),
            capabilityFile to capabilityBytes,
        )
        val source = WechatRulePackageSource { fileName, maximumBytes ->
            requireNotNull(files[fileName]).also { require(it.size <= maximumBytes) }.copyOf()
        }
        return Fixture(
            files,
            source,
            Base64.getEncoder().encodeToString(keyPair.public.encoded),
        )
    }

    private fun runtimeFacts(): WechatRuntimeFacts = WechatRuntimeFacts(
        applicationId = APPLICATION_ID,
        appVersion = APP_VERSION,
        appBuildSha256 = APP_BUILD_SHA256,
        signingCertificateSha256 = SIGNING_CERTIFICATE_SHA256,
        deviceManufacturer = DEVICE_MANUFACTURER,
        deviceModel = DEVICE_MODEL,
        androidSdkInt = 35,
        packageName = WECHAT_PACKAGE,
        wechatVersion = WECHAT_VERSION,
    )

    private fun combination(): WechatCapabilityCombination = WechatCapabilityCombination(
        applicationId = APPLICATION_ID,
        appVersion = APP_VERSION,
        appBuildSha256 = APP_BUILD_SHA256,
        signingCertificateSha256 = SIGNING_CERTIFICATE_SHA256,
        deviceManufacturer = DEVICE_MANUFACTURER,
        deviceModel = DEVICE_MODEL,
        minimumAndroidSdk = 29,
        maximumAndroidSdk = 35,
        packageName = WECHAT_PACKAGE,
        wechatVersion = WECHAT_VERSION,
        ruleVersion = RULE_VERSION,
        locatorVersion = LOCATOR_VERSION,
        compatibleMinimumRuleVersions = listOf(RULE_VERSION),
        allowedActions = listOf("SEND_AUDIO_AND_TEXT"),
        allowedPageTypes = mapOf("SEND_AUDIO_AND_TEXT" to listOf("DIRECT_CHAT")),
        allowedPageSignatures = mapOf("SEND_AUDIO_AND_TEXT" to listOf(PAGE_SIGNATURE)),
    )

    private fun placeholderPlan(): com.aifriend.contract.model.WechatActionPlan {
        val now = OffsetDateTime.parse("2026-08-19T08:00:00Z")
        return com.aifriend.contract.model.WechatActionPlan(
            planId = "wp_test",
            action = com.aifriend.contract.model.WechatActionType.SEND_AUDIO_AND_TEXT,
            contactId = "ct_test",
            summaryHash = "d".repeat(64),
            minimumRuleVersion = RULE_VERSION,
            expiresAt = now.plusSeconds(30),
            targetSearchLocator = "wxid_demo123",
            targetLocatorProof = com.aifriend.contract.model.WechatTargetLocatorProof(
                proofVersion = com.aifriend.contract.model.WechatTargetLocatorProof.ProofVersion
                    .WECHAT_LOCATOR_PROOF_V1,
                keyId = "test-plan-key",
                contactVersion = 1,
                wechatVersion = WECHAT_VERSION,
                locatorVersion = LOCATOR_VERSION,
                salt = "e".repeat(32),
                targetLocatorSha256 = "f".repeat(64),
                issuedAt = now,
                expiresAt = now.plusSeconds(30),
                signature = "A".repeat(86),
            ),
            audioObjectId = "ao_test",
        )
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private fun assertFails(block: () -> Unit) {
        assertTrue(runCatching(block).isFailure)
    }

    private data class Fixture(
        val files: MutableMap<String, ByteArray>,
        val source: WechatRulePackageSource,
        val publicKey: String,
    )

    private companion object {
        const val TRUSTED_KEY_ID = "test-rule-key-1"
        const val SCHEMA_VERSION = "WECHAT_CAPABILITY_MATRIX_V1"
        const val CAPABILITY_FILE = "capabilities.json"
        const val APPLICATION_ID = "com.aifriend.release"
        const val APP_VERSION = "1.0.0"
        const val APP_BUILD_SHA256 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SIGNING_CERTIFICATE_SHA256 =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val PAGE_SIGNATURE =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val DEVICE_MANUFACTURER = "test-manufacturer"
        const val DEVICE_MODEL = "test model"
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val WECHAT_VERSION = "8.0.50"
        const val RULE_VERSION = "wechat-rule-v1"
        const val LOCATOR_VERSION = "locator-v1"
    }
}
