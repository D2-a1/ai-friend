package com.aifriend.feature.wechat

import com.aifriend.contract.model.WechatActionPlan
import com.aifriend.contract.model.WechatActionType
import com.aifriend.contract.model.WechatTargetLocatorProof
import java.time.OffsetDateTime
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 微信五秒只读观察门闩、结构摘要、瞬时定位清零和非唯一拒绝测试。 */
class WechatReadOnlyPageObservationTest {
    private val now = OffsetDateTime.parse("2026-08-19T08:00:00Z")

    @Test
    fun exactOneTimeWindowRejectsWrongPackageExpiryAndReplay() {
        val broker = WechatPageObservationBroker()
        val plan = plan()
        val capability = capability(structureSignature())
        val proof = verifiedProof()
        broker.arm(plan, capability, proof, now.plusMinutes(1), now)

        assertNull(broker.activeRequest("other.package", now.plusSeconds(1)))
        val request = broker.activeRequest(WECHAT_PACKAGE, now.plusSeconds(1))
        assertEquals(plan.planId, request?.planId)
        val snapshot = snapshot(structureSignature(), now.plusSeconds(1))
        broker.publish(plan.planId, snapshot, now.plusSeconds(1))
        assertNull(broker.consume("other-plan", now.plusSeconds(1)))
        assertEquals(snapshot, broker.consume(plan.planId, now.plusSeconds(1)))
        assertNull(broker.consume(plan.planId, now.plusSeconds(1)))

        broker.arm(plan, capability, proof, now.plusMinutes(1), now)
        assertNull(broker.activeRequest(WECHAT_PACKAGE, now.plusSeconds(5)))
    }

    @Test
    fun callPlanWithMultipleSignedPagesStillStartsFromContactProfile() {
        val action = WechatActionType.START_VOICE_CALL
        val profileSignature = "1".repeat(64)
        val choiceSignature = "2".repeat(64)
        val activeSignature = "3".repeat(64)
        val capability = capability(profileSignature, WechatPageType.CONTACT_PROFILE).copy(
            allowedActions = setOf(action),
            allowedPageTypes = mapOf(
                action to setOf(
                    WechatPageType.CONTACT_PROFILE,
                    WechatPageType.VOICE_CALL_CONFIRMATION,
                    WechatPageType.VOICE_CALL_ACTIVE,
                ),
            ),
            allowedPageSignatures = mapOf(
                action to setOf(profileSignature, choiceSignature, activeSignature),
            ),
            allowedPageSignaturesByType = mapOf(
                action to mapOf(
                    WechatPageType.CONTACT_PROFILE to setOf(profileSignature),
                    WechatPageType.VOICE_CALL_CONFIRMATION to setOf(choiceSignature),
                    WechatPageType.VOICE_CALL_ACTIVE to setOf(activeSignature),
                ),
            ),
        )
        val callPlan = plan().copy(action = action, audioObjectId = null)
        val broker = WechatPageObservationBroker()

        broker.arm(callPlan, capability, verifiedProof(), now.plusMinutes(1), now)

        assertEquals(
            WechatPageType.CONTACT_PROFILE,
            broker.activeRequest(WECHAT_PACKAGE, now.plusSeconds(1))?.expectedPageType,
        )
    }

    @Test
    fun publishedSnapshotNotifiesCurrentTaskWithoutRetainingSensitiveContent() = runTest {
        val broker = WechatPageObservationBroker()
        val plan = plan()
        broker.arm(plan, capability(structureSignature()), verifiedProof(), now.plusMinutes(1), now)
        val signal = async(start = CoroutineStart.UNDISPATCHED) {
            broker.publishedPlans.first()
        }

        broker.publish(plan.planId, snapshot(structureSignature(), now.plusSeconds(1)), now.plusSeconds(1))

        assertEquals(plan.planId, signal.await())
    }

    @Test
    fun uniqueLocatorBecomesOnlySaltedDigestAndPlainCharsAreCleared() {
        val locator = "private-stable-locator".toCharArray()
        val digest = checkNotNull(WechatTargetLocatorDigest.compute(locator.copyOf(), SALT))
        val request = request(structureSignature(), digest)
        val evidence = WechatSensitivePageEvidence(listOf(locator), 1)

        val snapshot = WechatReadOnlySnapshotTranslator.translate(
            request,
            structures(),
            evidence,
            now.plusSeconds(1),
        )

        assertEquals(1, snapshot?.targetMatchCount)
        assertEquals(1, snapshot?.actionNodeMatchCount)
        assertEquals(digest, snapshot?.targetLocatorSha256)
        assertTrue(locator.all { it == '\u0000' })
        assertFalse(snapshot.toString().contains("private-stable-locator"))
        assertFalse(request.toString().contains(SALT))
        assertFalse(request.toString().contains(PLAN_ID))
    }

    @Test
    fun unavailableFormalNodeRulesProduceNonExecutableSanitizedSnapshot() {
        val request = request(structureSignature(), TARGET_DIGEST)

        val snapshot = WechatReadOnlySnapshotTranslator.translate(
            request,
            structures(),
            WechatSensitivePageEvidence.unavailable(),
            now.plusSeconds(1),
        )

        assertEquals(WechatPageType.DIRECT_CHAT, snapshot?.pageType)
        assertNull(snapshot?.targetLocatorSha256)
        assertEquals(0, snapshot?.targetMatchCount)
        assertEquals(0, snapshot?.actionNodeMatchCount)
    }

    @Test
    fun duplicateTargetAndActionNodesRemainCountsInsteadOfGuessingOne() {
        val first = "private-stable-locator".toCharArray()
        val second = "private-stable-locator".toCharArray()
        val digest = checkNotNull(WechatTargetLocatorDigest.compute(first.copyOf(), SALT))

        val snapshot = WechatReadOnlySnapshotTranslator.translate(
            request(structureSignature(), digest),
            structures(),
            WechatSensitivePageEvidence(listOf(first, second), 2),
            now.plusSeconds(1),
        )

        assertEquals(2, snapshot?.targetMatchCount)
        assertEquals(2, snapshot?.actionNodeMatchCount)
        assertTrue(first.all { it == '\u0000' })
        assertTrue(second.all { it == '\u0000' })
    }

    @Test
    fun narrowSignedContactProfileEvidenceCanSupplyItsOwnSanitizedSignature() {
        val locator = "private-stable-locator".toCharArray()
        val digest = checkNotNull(WechatTargetLocatorDigest.compute(locator.copyOf(), SALT))
        val signature = "c".repeat(64)
        val request = request(signature, digest, WechatPageType.CONTACT_PROFILE)

        val snapshot = WechatReadOnlySnapshotTranslator.translate(
            request,
            emptyList(),
            WechatSensitivePageEvidence(listOf(locator), 1, signature),
            now.plusSeconds(1),
        )

        assertEquals(WechatPageType.CONTACT_PROFILE, snapshot?.pageType)
        assertEquals(signature, snapshot?.pageSignatureSha256)
        assertEquals(1, snapshot?.targetMatchCount)
        assertEquals(1, snapshot?.actionNodeMatchCount)
        assertTrue(locator.all { it == '\u0000' })
    }

    @Test
    fun formalEvidencePolicyOnlyAllowsExactMessageContactProfileRule() {
        val approved = request(
            structureSignature(),
            TARGET_DIGEST,
            WechatPageType.CONTACT_PROFILE,
            WechatLocalVerificationSession.RULE_VERSION,
        )
        val directChat = request(
            structureSignature(),
            TARGET_DIGEST,
            WechatPageType.DIRECT_CHAT,
            WechatLocalVerificationSession.RULE_VERSION,
        )
        val wrongLocatorVersion = request(
            structureSignature(),
            TARGET_DIGEST,
            WechatPageType.CONTACT_PROFILE,
            "other-rule",
        )

        assertTrue(WechatContactProfileEvidencePolicy.supports(approved))
        assertFalse(WechatContactProfileEvidencePolicy.supports(directChat))
        assertFalse(WechatContactProfileEvidencePolicy.supports(wrongLocatorVersion))
    }

    @Test
    fun pageSpecificSignaturesNeverConfuseProfileWithDirectChat() {
        val profileSignature = "c".repeat(64)
        val directChatSignature = "d".repeat(64)
        val base = capability(profileSignature, WechatPageType.CONTACT_PROFILE)
        val multiPageCapability = base.copy(
            allowedPageTypes = mapOf(
                WechatActionType.SEND_AUDIO_AND_TEXT to setOf(
                    WechatPageType.CONTACT_PROFILE,
                    WechatPageType.DIRECT_CHAT,
                ),
            ),
            allowedPageSignatures = mapOf(
                WechatActionType.SEND_AUDIO_AND_TEXT to
                    setOf(profileSignature, directChatSignature),
            ),
            allowedPageSignaturesByType = mapOf(
                WechatActionType.SEND_AUDIO_AND_TEXT to mapOf(
                    WechatPageType.CONTACT_PROFILE to setOf(profileSignature),
                    WechatPageType.DIRECT_CHAT to setOf(directChatSignature),
                ),
            ),
        )
        val request = request(profileSignature, TARGET_DIGEST, WechatPageType.CONTACT_PROFILE)
            .copy(capability = multiPageCapability)

        assertEquals(
            WechatPageType.CONTACT_PROFILE,
            WechatReadOnlySnapshotTranslator.translate(
                request,
                emptyList(),
                WechatSensitivePageEvidence(emptyList(), 0, profileSignature),
                now.plusSeconds(1),
            )?.pageType,
        )
        assertNull(
            WechatReadOnlySnapshotTranslator.translate(
                request.copy(expectedPageType = WechatPageType.DIRECT_CHAT),
                emptyList(),
                WechatSensitivePageEvidence(emptyList(), 0, profileSignature),
                now.plusSeconds(1),
            ),
        )
    }

    @Test
    fun wrongSignatureExpiredWindowAndOversizedEvidenceFailClosedAndClear() {
        val wrongSignature = WechatReadOnlySnapshotTranslator.translate(
            request("f".repeat(64), TARGET_DIGEST),
            structures(),
            WechatSensitivePageEvidence.unavailable(),
            now.plusSeconds(1),
        )
        assertNull(wrongSignature)

        val expired = WechatReadOnlySnapshotTranslator.translate(
            request(structureSignature(), TARGET_DIGEST),
            structures(),
            WechatSensitivePageEvidence.unavailable(),
            now.plusSeconds(6),
        )
        assertNull(expired)

        val candidates = List(9) { "locator-$it".toCharArray() }
        val oversized = WechatReadOnlySnapshotTranslator.translate(
            request(structureSignature(), TARGET_DIGEST),
            structures(),
            WechatSensitivePageEvidence(candidates, 1),
            now.plusSeconds(1),
        )
        assertNull(oversized)
        assertTrue(candidates.all { candidate -> candidate.all { it == '\u0000' } })
    }

    @Test
    fun structureCanonicalizerRejectsUnboundedNodeLists() {
        val oversized = List(WechatPageStructureCanonicalizer.MAXIMUM_NODE_COUNT + 1) {
            WechatStructuralNode(0, 0, 0, "android.view.View", "")
        }

        assertTrue(runCatching { WechatPageStructureCanonicalizer.sha256(oversized) }.isFailure)
    }

    private fun request(
        signature: String,
        targetDigest: String,
        pageType: WechatPageType = WechatPageType.DIRECT_CHAT,
        locatorVersion: String = LOCATOR_VERSION,
    ): WechatPageObservationRequest =
        WechatPageObservationRequest(
            planId = PLAN_ID,
            action = WechatActionType.SEND_AUDIO_AND_TEXT,
            capability = capability(signature, pageType, locatorVersion),
            targetLocatorProof = verifiedProof(targetDigest).copy(locatorVersion = locatorVersion),
            locatorSalt = SALT,
            openedAt = now,
            expiresAt = now.plusSeconds(5),
            expectedPageType = pageType,
        )

    private fun plan(): WechatActionPlan = WechatActionPlan(
        planId = PLAN_ID,
        action = WechatActionType.SEND_AUDIO_AND_TEXT,
        contactId = CONTACT_ID,
        summaryHash = SUMMARY_HASH,
        minimumRuleVersion = RULE_VERSION,
        expiresAt = now.plusSeconds(30),
        targetSearchLocator = "wxid_demo123",
        targetLocatorProof = WechatTargetLocatorProof(
            proofVersion = WechatTargetLocatorProof.ProofVersion.WECHAT_LOCATOR_PROOF_V1,
            keyId = "test-key",
            contactVersion = 1,
            wechatVersion = WECHAT_VERSION,
            locatorVersion = LOCATOR_VERSION,
            salt = SALT,
            targetLocatorSha256 = TARGET_DIGEST,
            issuedAt = now,
            expiresAt = now.plusSeconds(30),
            signature = "A".repeat(86),
        ),
        audioObjectId = "ao_test",
    )

    private fun verifiedProof(
        targetDigest: String = TARGET_DIGEST,
    ): VerifiedWechatTargetLocatorProof = VerifiedWechatTargetLocatorProof(
        planId = PLAN_ID,
        contactId = CONTACT_ID,
        contactVersion = 1,
        wechatVersion = WECHAT_VERSION,
        locatorVersion = LOCATOR_VERSION,
        targetLocatorSha256 = targetDigest,
        issuedAt = now,
        expiresAt = now.plusSeconds(30),
        keyId = "test-key",
    )

    private fun capability(
        signature: String,
        pageType: WechatPageType = WechatPageType.DIRECT_CHAT,
        locatorVersion: String = LOCATOR_VERSION,
    ): WechatCapabilitySnapshot =
        WechatCapabilitySnapshot(
            remotelyEnabled = true,
            signedRulesTrusted = true,
            combinationApproved = true,
            packageName = WECHAT_PACKAGE,
            wechatVersion = WECHAT_VERSION,
            ruleVersion = RULE_VERSION,
            locatorVersion = locatorVersion,
            compatibleMinimumRuleVersions = setOf(RULE_VERSION),
            allowedActions = setOf(WechatActionType.SEND_AUDIO_AND_TEXT),
            allowedPageTypes = mapOf(
                WechatActionType.SEND_AUDIO_AND_TEXT to setOf(pageType),
            ),
            allowedPageSignatures = mapOf(
                WechatActionType.SEND_AUDIO_AND_TEXT to setOf(signature),
            ),
            appBuildSha256 = "a".repeat(64),
            signingCertificateSha256 = "b".repeat(64),
            deviceManufacturer = "test-manufacturer",
            deviceModel = "test-model",
            androidSdkInt = 35,
        )

    private fun structures(): List<WechatStructuralNode> = listOf(
        WechatStructuralNode(0, 0, 1, "android.widget.FrameLayout", "com.tencent.mm:id/root"),
        WechatStructuralNode(1, 0, 0, "android.widget.LinearLayout", "com.tencent.mm:id/body"),
    )

    private fun structureSignature(): String = WechatPageStructureCanonicalizer.sha256(structures())

    private fun snapshot(signature: String, capturedAt: OffsetDateTime): WechatPageSnapshot =
        WechatPageSnapshot(
            packageName = WECHAT_PACKAGE,
            wechatVersion = WECHAT_VERSION,
            ruleVersion = RULE_VERSION,
            locatorVersion = LOCATOR_VERSION,
            pageType = WechatPageType.DIRECT_CHAT,
            pageSignatureSha256 = signature,
            targetLocatorSha256 = null,
            targetMatchCount = 0,
            actionNodeMatchCount = 0,
            capturedAt = capturedAt,
        )

    private companion object {
        const val PLAN_ID = "wp_test_plan"
        const val CONTACT_ID = "ct_test_contact"
        const val SUMMARY_HASH =
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        const val SALT = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val TARGET_DIGEST =
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val WECHAT_VERSION = "8.0.50"
        const val RULE_VERSION = "wechat-rule-v1"
        const val LOCATOR_VERSION = "locator-v1"
    }
}
