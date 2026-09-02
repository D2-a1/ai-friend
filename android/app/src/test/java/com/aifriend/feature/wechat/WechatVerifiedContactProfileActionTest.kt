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

/** 二次准入后的单次资料页动作门闩与点击前纯复验测试。 */
class WechatVerifiedContactProfileActionTest {
    private val now = OffsetDateTime.parse("2026-08-30T12:00:00Z")

    @Test
    fun requestIsConsumedBeforeActionAndCannotReplay() = runTest {
        val broker = WechatVerifiedContactProfileActionBroker()
        val requestSignal = async(start = CoroutineStart.UNDISPATCHED) {
            broker.requests.first()
        }

        broker.arm(plan(), capability(), proof(), now)

        assertEquals(PLAN_ID, requestSignal.await())
        assertNull(broker.take(PLAN_ID, "other.package", now.plusNanos(1)))
        assertEquals(
            PLAN_ID,
            broker.take(PLAN_ID, WECHAT_PACKAGE, now.plusNanos(1))?.planId,
        )
        assertNull(broker.take(PLAN_ID, WECHAT_PACKAGE, now.plusNanos(1)))
    }

    @Test
    fun missingAccessibilitySubscriberFailsClosedWithoutRetainingAction() = runTest {
        val broker = WechatVerifiedContactProfileActionBroker()
        val outcome = async(start = CoroutineStart.UNDISPATCHED) {
            broker.outcomes.first()
        }

        broker.arm(plan(), capability(), proof(), now)

        assertEquals(
            WechatVerifiedContactProfileActionStatus.SERVICE_UNAVAILABLE,
            outcome.await().status,
        )
        assertNull(broker.take(PLAN_ID, WECHAT_PACKAGE, now.plusNanos(1)))
    }

    @Test
    fun serviceInterruptionReportsFailureAndNeverLeavesPendingReplay() = runTest {
        val broker = WechatVerifiedContactProfileActionBroker()
        val requestSignal = async(start = CoroutineStart.UNDISPATCHED) {
            broker.requests.first()
        }
        val outcome = async(start = CoroutineStart.UNDISPATCHED) {
            broker.outcomes.first()
        }

        broker.arm(plan(), capability(), proof(), now)
        assertEquals(PLAN_ID, requestSignal.await())
        broker.interrupt()

        assertEquals(
            WechatVerifiedContactProfileActionStatus.SERVICE_UNAVAILABLE,
            outcome.await().status,
        )
        assertNull(broker.take(PLAN_ID, WECHAT_PACKAGE, now.plusNanos(1)))
    }

    @Test
    fun onlyExactSignedMessageContactProfileCanBeArmed() = runTest {
        val broker = WechatVerifiedContactProfileActionBroker()
        val wrongPageCapability = capability().copy(
            allowedPageTypes = mapOf(
                WechatActionType.SEND_AUDIO_AND_TEXT to setOf(WechatPageType.DIRECT_CHAT),
            ),
        )

        broker.arm(plan(), wrongPageCapability, proof(), now)

        assertNull(broker.take(PLAN_ID, WECHAT_PACKAGE, now.plusNanos(1)))
    }

    @Test
    fun voiceCallUsesItsOwnSignedProfileAndChoicePage() = runTest {
        val broker = WechatVerifiedContactProfileActionBroker()
        val requestSignal = async(start = CoroutineStart.UNDISPATCHED) {
            broker.requests.first()
        }
        val action = WechatActionType.START_VOICE_CALL

        broker.arm(plan(action), capability(action), proof(), now)

        assertEquals(PLAN_ID, requestSignal.await())
        assertEquals(
            action,
            broker.take(PLAN_ID, WECHAT_PACKAGE, now.plusNanos(1))?.action,
        )
    }

    @Test
    fun exactRevalidatedSnapshotIsAccepted() {
        val request = request()

        assertTrue(
            WechatVerifiedContactProfileActionPolicy.accepts(
                request,
                snapshot(),
                now.plusNanos(1),
            ),
        )
    }

    @Test
    fun changedLocatorDuplicateActionAndExpiryAreRejected() {
        val request = request()

        assertFalse(
            WechatVerifiedContactProfileActionPolicy.accepts(
                request,
                snapshot().copy(targetLocatorSha256 = "9".repeat(64)),
                now.plusNanos(1),
            ),
        )
        assertFalse(
            WechatVerifiedContactProfileActionPolicy.accepts(
                request,
                snapshot().copy(actionNodeMatchCount = 2),
                now.plusNanos(1),
            ),
        )
        assertFalse(
            WechatVerifiedContactProfileActionPolicy.accepts(
                request,
                snapshot().copy(capturedAt = now.plusSeconds(3)),
                now.plusSeconds(3),
            ),
        )
    }

    private fun request(): WechatVerifiedContactProfileActionRequest =
        WechatVerifiedContactProfileActionRequest(
            planId = PLAN_ID,
            action = WechatActionType.SEND_AUDIO_AND_TEXT,
            capability = capability(),
            targetLocatorProof = proof(),
            locatorSalt = SALT,
            openedAt = now,
            expiresAt = now.plusSeconds(2),
        )

    private fun plan(
        action: WechatActionType = WechatActionType.SEND_AUDIO_AND_TEXT,
    ): WechatActionPlan = WechatActionPlan(
        planId = PLAN_ID,
        action = action,
        contactId = CONTACT_ID,
        summaryHash = "a".repeat(64),
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
        audioObjectId = "ao_test".takeIf { action == WechatActionType.SEND_AUDIO_AND_TEXT },
    )

    private fun proof(): VerifiedWechatTargetLocatorProof = VerifiedWechatTargetLocatorProof(
        planId = PLAN_ID,
        contactId = CONTACT_ID,
        contactVersion = 1,
        wechatVersion = WECHAT_VERSION,
        locatorVersion = LOCATOR_VERSION,
        targetLocatorSha256 = TARGET_DIGEST,
        issuedAt = now,
        expiresAt = now.plusSeconds(30),
        keyId = "test-key",
    )

    private fun capability(
        action: WechatActionType = WechatActionType.SEND_AUDIO_AND_TEXT,
    ): WechatCapabilitySnapshot = WechatCapabilitySnapshot(
        remotelyEnabled = true,
        signedRulesTrusted = true,
        combinationApproved = true,
        packageName = WECHAT_PACKAGE,
        wechatVersion = WECHAT_VERSION,
        ruleVersion = RULE_VERSION,
        locatorVersion = LOCATOR_VERSION,
        compatibleMinimumRuleVersions = setOf(RULE_VERSION),
        allowedActions = setOf(action),
        allowedPageTypes = mapOf(
            action to setOf(
                WechatPageType.CONTACT_PROFILE,
                action.transitionPageType(),
            ),
        ),
        allowedPageSignatures = mapOf(
            action to setOf(
                PAGE_SIGNATURE,
                DIRECT_CHAT_SIGNATURE,
            ),
        ),
        appBuildSha256 = "b".repeat(64),
        signingCertificateSha256 = "c".repeat(64),
        deviceManufacturer = "test-manufacturer",
        deviceModel = "test-model",
        androidSdkInt = 35,
        allowedPageSignaturesByType = mapOf(
            action to mapOf(
                WechatPageType.CONTACT_PROFILE to setOf(PAGE_SIGNATURE),
                action.transitionPageType() to setOf(DIRECT_CHAT_SIGNATURE),
            ),
        ),
    )

    private fun snapshot(): WechatPageSnapshot = WechatPageSnapshot(
        packageName = WECHAT_PACKAGE,
        wechatVersion = WECHAT_VERSION,
        ruleVersion = RULE_VERSION,
        locatorVersion = LOCATOR_VERSION,
        pageType = WechatPageType.CONTACT_PROFILE,
        pageSignatureSha256 = PAGE_SIGNATURE,
        targetLocatorSha256 = TARGET_DIGEST,
        targetMatchCount = 1,
        actionNodeMatchCount = 1,
        capturedAt = now.plusNanos(1),
    )

    private companion object {
        const val PLAN_ID = "wp_test_plan"
        const val CONTACT_ID = "ct_test_contact"
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val WECHAT_VERSION = "8.0.76"
        const val RULE_VERSION = "wechat-rule-v1"
        const val LOCATOR_VERSION = "wechat-contact-profile-v1"
        const val SALT = "1a2b3c4d5e6f708192a3b4c5d6e7f809"
        const val TARGET_DIGEST =
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        const val PAGE_SIGNATURE =
            "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val DIRECT_CHAT_SIGNATURE =
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
    }
}
