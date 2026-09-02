package com.aifriend.feature.wechat

import com.aifriend.contract.model.WechatActionType
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

/** 资料页点击后的短时聊天页结构确认与失败关闭测试。 */
class WechatDirectChatTransitionTest {
    private val now = OffsetDateTime.parse("2026-08-30T12:00:00Z")

    @Test
    fun exactSignedDirectChatStructureConfirmsOnlyOnce() = runTest {
        val broker = WechatDirectChatTransitionBroker()
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { broker.outcomes.first() }
        assertTrue(broker.arm(actionRequest(), now))
        val request = broker.activeRequest(WECHAT_PACKAGE, now.plusSeconds(1))

        assertTrue(
            broker.observe(
                PLAN_ID,
                WECHAT_PACKAGE,
                sample(DIRECT_CHAT_SIGNATURE, now.plusSeconds(1)),
                now.plusSeconds(1),
            ),
        )
        assertEquals(WechatDirectChatTransitionStatus.CONFIRMED, outcome.await().status)
        assertNull(request?.let { broker.activeRequest(WECHAT_PACKAGE, now.plusSeconds(1)) })
    }

    @Test
    fun profileSignatureWrongPackageAndExpiredSampleNeverConfirm() {
        val broker = WechatDirectChatTransitionBroker()
        assertTrue(broker.arm(actionRequest(), now))

        assertNull(broker.activeRequest("other.package", now.plusSeconds(1)))
        assertFalse(
            broker.observe(
                PLAN_ID,
                WECHAT_PACKAGE,
                sample(PROFILE_SIGNATURE, now.plusSeconds(1)),
                now.plusSeconds(1),
            ),
        )
        assertFalse(
            broker.observe(
                PLAN_ID,
                WECHAT_PACKAGE,
                sample(DIRECT_CHAT_SIGNATURE, now.plusSeconds(4)),
                now.plusSeconds(4),
            ),
        )
    }

    @Test
    fun timeoutAndServiceInterruptionClearPendingRequestWithFiniteFailure() = runTest {
        val timedOut = WechatDirectChatTransitionBroker()
        val timeoutOutcome = async(start = CoroutineStart.UNDISPATCHED) {
            timedOut.outcomes.first()
        }
        assertTrue(timedOut.arm(actionRequest(), now))
        timedOut.expire(PLAN_ID, now.plusSeconds(4))
        assertEquals(
            WechatDirectChatTransitionStatus.NOT_CONFIRMED,
            timeoutOutcome.await().status,
        )
        assertNull(timedOut.activeRequest(WECHAT_PACKAGE, now.plusSeconds(1)))

        val interrupted = WechatDirectChatTransitionBroker()
        val interruptOutcome = async(start = CoroutineStart.UNDISPATCHED) {
            interrupted.outcomes.first()
        }
        assertTrue(interrupted.arm(actionRequest(), now))
        interrupted.interrupt()
        assertEquals(
            WechatDirectChatTransitionStatus.SERVICE_UNAVAILABLE,
            interruptOutcome.await().status,
        )
        assertNull(interrupted.activeRequest(WECHAT_PACKAGE, now.plusSeconds(1)))
    }

    @Test
    fun lateWechatEventAlsoReturnsFiniteTimeoutFailure() = runTest {
        val broker = WechatDirectChatTransitionBroker()
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { broker.outcomes.first() }
        assertTrue(broker.arm(actionRequest(), now))

        assertNull(broker.activeRequest(WECHAT_PACKAGE, now.plusSeconds(4)))
        assertEquals(
            WechatDirectChatTransitionStatus.NOT_CONFIRMED,
            outcome.await().status,
        )
    }

    @Test
    fun missingDirectChatRuleRefusesToArm() = runTest {
        val broker = WechatDirectChatTransitionBroker()
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { broker.outcomes.first() }
        val source = actionRequest().copy(
            capability = capability().copy(
                allowedPageTypes = mapOf(
                    WechatActionType.SEND_AUDIO_AND_TEXT to
                        setOf(WechatPageType.CONTACT_PROFILE),
                ),
                allowedPageSignatures = mapOf(
                    WechatActionType.SEND_AUDIO_AND_TEXT to setOf(PROFILE_SIGNATURE),
                ),
                allowedPageSignaturesByType = mapOf(
                    WechatActionType.SEND_AUDIO_AND_TEXT to mapOf(
                        WechatPageType.CONTACT_PROFILE to setOf(PROFILE_SIGNATURE),
                    ),
                ),
            ),
        )

        assertFalse(broker.arm(source, now))
        assertEquals(WechatDirectChatTransitionStatus.NOT_CONFIRMED, outcome.await().status)
    }

    @Test
    fun voiceCallConfirmsOnlyItsSignedChoicePageAndTransfersOnce() = runTest {
        val broker = WechatDirectChatTransitionBroker()
        val action = WechatActionType.START_VOICE_CALL
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { broker.outcomes.first() }
        assertTrue(broker.arm(actionRequest(action), now))

        assertFalse(
            broker.observe(
                PLAN_ID,
                WECHAT_PACKAGE,
                sample(DIRECT_CHAT_SIGNATURE, now.plusNanos(1)),
                now.plusNanos(1),
            ),
        )
        assertTrue(
            broker.observe(
                PLAN_ID,
                WECHAT_PACKAGE,
                sample(CALL_CHOICE_SIGNATURE, now.plusNanos(2)),
                now.plusNanos(2),
            ),
        )
        assertEquals(WechatDirectChatTransitionStatus.CONFIRMED, outcome.await().status)
        assertEquals(action, broker.takeConfirmed(PLAN_ID)?.action)
        assertNull(broker.takeConfirmed(PLAN_ID))
    }

    private fun actionRequest(
        action: WechatActionType = WechatActionType.SEND_AUDIO_AND_TEXT,
    ) = WechatVerifiedContactProfileActionRequest(
        planId = PLAN_ID,
        action = action,
        capability = capability(action),
        targetLocatorProof = VerifiedWechatTargetLocatorProof(
            planId = PLAN_ID,
            contactId = "ct_test",
            contactVersion = 1,
            wechatVersion = WECHAT_VERSION,
            locatorVersion = LOCATOR_VERSION,
            targetLocatorSha256 = "a".repeat(64),
            issuedAt = now,
            expiresAt = now.plusSeconds(30),
            keyId = "test-key",
        ),
        locatorSalt = "b".repeat(32),
        openedAt = now.minusSeconds(1),
        expiresAt = now.plusSeconds(1),
    )

    private fun capability(
        action: WechatActionType = WechatActionType.SEND_AUDIO_AND_TEXT,
    ) = WechatCapabilitySnapshot(
        remotelyEnabled = true,
        signedRulesTrusted = true,
        combinationApproved = true,
        packageName = WECHAT_PACKAGE,
        wechatVersion = WECHAT_VERSION,
        ruleVersion = "rule-v1",
        locatorVersion = LOCATOR_VERSION,
        compatibleMinimumRuleVersions = setOf("rule-v1"),
        allowedActions = setOf(action),
        allowedPageTypes = mapOf(
            action to setOf(
                WechatPageType.CONTACT_PROFILE,
                action.transitionPageType(),
            ),
        ),
        allowedPageSignatures = mapOf(
            action to setOf(
                PROFILE_SIGNATURE,
                if (action == WechatActionType.SEND_AUDIO_AND_TEXT) {
                    DIRECT_CHAT_SIGNATURE
                } else {
                    CALL_CHOICE_SIGNATURE
                },
            ),
        ),
        appBuildSha256 = "c".repeat(64),
        signingCertificateSha256 = "d".repeat(64),
        deviceManufacturer = "test-manufacturer",
        deviceModel = "test-model",
        androidSdkInt = 35,
        allowedPageSignaturesByType = mapOf(
            action to mapOf(
                WechatPageType.CONTACT_PROFILE to setOf(PROFILE_SIGNATURE),
                action.transitionPageType() to setOf(
                    if (action == WechatActionType.SEND_AUDIO_AND_TEXT) {
                        DIRECT_CHAT_SIGNATURE
                    } else {
                        CALL_CHOICE_SIGNATURE
                    },
                ),
            ),
        ),
    )

    private fun sample(signature: String, at: OffsetDateTime) =
        WechatPageStructureSample(signature, 5, at)

    private companion object {
        const val PLAN_ID = "wp_transition_test"
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val WECHAT_VERSION = "8.0.76"
        const val LOCATOR_VERSION = "wechat-contact-profile-v1"
        const val PROFILE_SIGNATURE =
            "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val DIRECT_CHAT_SIGNATURE =
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        const val CALL_CHOICE_SIGNATURE =
            "abababababababababababababababababababababababababababababababab"
    }
}
