package com.aifriend.feature.wechat

import com.aifriend.contract.model.ChannelPartResult
import com.aifriend.contract.model.ChannelResult
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

/** 最终点击后签名通话中页面的短时确认与保守回落测试。 */
class WechatCallStartedTransitionTest {
    private val now = OffsetDateTime.parse("2026-08-31T02:00:00Z")

    @Test
    fun voiceAndVideoUseDifferentActivePageTypes() {
        assertEquals(
            WechatPageType.VOICE_CALL_ACTIVE,
            WechatActionType.START_VOICE_CALL.activeCallPageType(),
        )
        assertEquals(
            WechatPageType.VIDEO_CALL_ACTIVE,
            WechatActionType.START_VIDEO_CALL.activeCallPageType(),
        )
    }

    @Test
    fun missingActivePageSignatureDoesNotArm() {
        val broker = WechatCallStartedTransitionBroker()

        assertFalse(broker.arm(choiceRequest(capability(includeActive = false)), now))
        assertNull(broker.activeRequest(WECHAT_PACKAGE, now.plusNanos(1)))
    }

    @Test
    fun exactFreshSignedActivePageConfirmsOnce() = runTest {
        val broker = WechatCallStartedTransitionBroker()
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { broker.outcomes.first() }

        assertTrue(broker.arm(choiceRequest(capability()), now))
        val request = requireNotNull(broker.activeRequest(WECHAT_PACKAGE, now.plusNanos(1)))
        assertTrue(
            broker.observe(
                request.planId,
                WECHAT_PACKAGE,
                sample(ACTIVE_SIGNATURE, now.plusNanos(2)),
                now.plusNanos(3),
            ),
        )

        assertEquals(WechatCallStartedTransitionStatus.CONFIRMED, outcome.await().status)
        assertNull(broker.activeRequest(WECHAT_PACKAGE, now.plusNanos(4)))
    }

    @Test
    fun wrongPageNeverConfirmsAndExpiresAsUnconfirmed() = runTest {
        val broker = WechatCallStartedTransitionBroker()
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { broker.outcomes.first() }
        assertTrue(broker.arm(choiceRequest(capability()), now))
        val request = requireNotNull(broker.activeRequest(WECHAT_PACKAGE, now.plusNanos(1)))

        assertFalse(
            broker.observe(
                request.planId,
                WECHAT_PACKAGE,
                sample("9".repeat(64), now.plusNanos(2)),
                now.plusNanos(3),
            ),
        )
        broker.expire(PLAN_ID, now.plusSeconds(4))

        assertEquals(WechatCallStartedTransitionStatus.NOT_CONFIRMED, outcome.await().status)
    }

    @Test
    fun confirmedPageReportsCallStartedWithoutClaimingAnswer() {
        val report = WechatCallStartedTransitionOutcome(
            PLAN_ID,
            WechatActionType.START_VOICE_CALL,
            WechatCallStartedTransitionStatus.CONFIRMED,
        ).toDeliveryReport()

        assertEquals(ChannelResult.CALL_STARTED, report.result)
        assertEquals(ChannelPartResult.Result.CALL_STARTED, report.parts.single().result)
        assertEquals("VOICE_CALL_ACTIVE_PAGE_CONFIRMED", report.parts.single().evidenceCode)
    }

    @Test
    fun unavailableObservationFailsClosedInsteadOfInventingHandoffOrStart() {
        val report = WechatCallStartedTransitionOutcome(
            PLAN_ID,
            WechatActionType.START_VIDEO_CALL,
            WechatCallStartedTransitionStatus.SERVICE_UNAVAILABLE,
        ).toDeliveryReport()

        assertEquals(ChannelResult.FAILED, report.result)
        assertEquals(ChannelPartResult.Result.FAILED, report.parts.single().result)
        assertEquals(
            "VIDEO_CALL_ACTIVE_PAGE_SERVICE_UNAVAILABLE",
            report.parts.single().evidenceCode,
        )
    }

    private fun choiceRequest(capability: WechatCapabilitySnapshot) =
        WechatCallChoiceActionRequest(
            planId = PLAN_ID,
            action = WechatActionType.START_VOICE_CALL,
            expectedPageType = WechatPageType.VOICE_CALL_CONFIRMATION,
            capability = capability,
            openedAt = now.minusSeconds(1),
            expiresAt = now.plusSeconds(1),
        )

    private fun sample(signature: String, capturedAt: OffsetDateTime) =
        WechatPageStructureSample(signature, 9, capturedAt)

    private fun capability(includeActive: Boolean = true): WechatCapabilitySnapshot {
        val pageTypes = buildSet {
            add(WechatPageType.CONTACT_PROFILE)
            add(WechatPageType.VOICE_CALL_CONFIRMATION)
            if (includeActive) add(WechatPageType.VOICE_CALL_ACTIVE)
        }
        val signatures = buildMap {
            put(WechatPageType.CONTACT_PROFILE, setOf(PROFILE_SIGNATURE))
            put(WechatPageType.VOICE_CALL_CONFIRMATION, setOf(CHOICE_SIGNATURE))
            if (includeActive) put(WechatPageType.VOICE_CALL_ACTIVE, setOf(ACTIVE_SIGNATURE))
        }
        return WechatCapabilitySnapshot(
            remotelyEnabled = true,
            signedRulesTrusted = true,
            combinationApproved = true,
            packageName = WECHAT_PACKAGE,
            wechatVersion = "8.0.76",
            ruleVersion = "wechat-rule-v1",
            locatorVersion = "wechat-contact-profile-v1",
            compatibleMinimumRuleVersions = setOf("wechat-rule-v1"),
            allowedActions = setOf(WechatActionType.START_VOICE_CALL),
            allowedPageTypes = mapOf(WechatActionType.START_VOICE_CALL to pageTypes),
            allowedPageSignatures = mapOf(
                WechatActionType.START_VOICE_CALL to signatures.values.flatten().toSet(),
            ),
            appBuildSha256 = "a".repeat(64),
            signingCertificateSha256 = "b".repeat(64),
            deviceManufacturer = "test-manufacturer",
            deviceModel = "test-model",
            androidSdkInt = 35,
            allowedPageSignaturesByType = mapOf(
                WechatActionType.START_VOICE_CALL to signatures,
            ),
        )
    }

    private companion object {
        const val PLAN_ID = "wp_started_plan"
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val PROFILE_SIGNATURE =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val CHOICE_SIGNATURE =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val ACTIVE_SIGNATURE =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
