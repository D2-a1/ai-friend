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

/** 通话选择页短时门闩、精确类型和有限渠道结果测试。 */
class WechatCallChoiceActionTest {
    private val now = OffsetDateTime.parse("2026-08-30T14:00:00Z")

    @Test
    fun voiceAndVideoLabelsNeverCrossMatch() {
        assertTrue(WechatCallChoiceTextRule.matches(" 语音通话 ", WechatActionType.START_VOICE_CALL))
        assertTrue(WechatCallChoiceTextRule.matches("视频通话", WechatActionType.START_VIDEO_CALL))
        assertFalse(WechatCallChoiceTextRule.matches("视频通话", WechatActionType.START_VOICE_CALL))
        assertFalse(WechatCallChoiceTextRule.matches("语音通话", WechatActionType.START_VIDEO_CALL))
    }

    @Test
    fun requestIsConsumedOnceOnlyByWechatPackage() = runTest {
        val broker = WechatCallChoiceActionBroker()
        val signal = async(start = CoroutineStart.UNDISPATCHED) { broker.requests.first() }
        val transition = transition()

        broker.arm(transition, now)

        assertEquals(PLAN_ID, signal.await())
        assertNull(broker.take(PLAN_ID, "other.package", now.plusNanos(1)))
        assertEquals(
            WechatActionType.START_VOICE_CALL,
            broker.take(PLAN_ID, WECHAT_PACKAGE, now.plusNanos(1))?.action,
        )
        assertNull(broker.take(PLAN_ID, WECHAT_PACKAGE, now.plusNanos(1)))
    }

    @Test
    fun missingSubscriberFailsClosedWithoutReplay() = runTest {
        val broker = WechatCallChoiceActionBroker()
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { broker.outcomes.first() }

        broker.arm(transition(), now)

        assertEquals(WechatCallChoiceActionStatus.SERVICE_UNAVAILABLE, outcome.await().status)
        assertNull(broker.take(PLAN_ID, WECHAT_PACKAGE, now.plusNanos(1)))
    }

    @Test
    fun onlyFreshExactSignedChoicePageIsAccepted() {
        val request = request()
        val exact = sample()

        assertTrue(WechatCallChoiceActionPolicy.accepts(request, exact, now.plusNanos(2)))
        assertFalse(
            WechatCallChoiceActionPolicy.accepts(
                request,
                exact.copy(signatureSha256 = "9".repeat(64)),
                now.plusNanos(2),
            ),
        )
        assertFalse(
            WechatCallChoiceActionPolicy.accepts(
                request,
                exact.copy(capturedAt = now.plusSeconds(3)),
                now.plusSeconds(3),
            ),
        )
    }

    @Test
    fun acceptedClickReportsOpenedButNeverCallStarted() {
        val report = WechatCallChoiceActionOutcome(
            PLAN_ID,
            WechatActionType.START_VOICE_CALL,
            WechatCallChoiceActionStatus.CLICK_REQUEST_ACCEPTED,
        ).toDeliveryReport()

        assertEquals(ChannelResult.OPENED, report.result)
        assertEquals(ChannelPartResult.Part.CALL, report.parts.single().part)
        assertEquals(ChannelPartResult.Result.HANDED_TO_WECHAT, report.parts.single().result)
        assertFalse(report.parts.single().result == ChannelPartResult.Result.CALL_STARTED)
    }

    @Test
    fun rejectedClickReportsFailedWithoutCallStarted() {
        val report = WechatCallChoiceActionOutcome(
            PLAN_ID,
            WechatActionType.START_VIDEO_CALL,
            WechatCallChoiceActionStatus.CLICK_REQUEST_REJECTED,
        ).toDeliveryReport()

        assertEquals(ChannelResult.FAILED, report.result)
        assertEquals(ChannelPartResult.Result.FAILED, report.parts.single().result)
        assertFalse(report.parts.single().result == ChannelPartResult.Result.CALL_STARTED)
    }

    @Test
    fun microphoneReleaseFailureReportsFailedWithoutClickSuccess() {
        val report = WechatCallChoiceActionOutcome(
            PLAN_ID,
            WechatActionType.START_VOICE_CALL,
            WechatCallChoiceActionStatus.AUDIO_RELEASE_FAILED,
        ).toDeliveryReport()

        assertEquals(ChannelResult.FAILED, report.result)
        assertEquals(ChannelPartResult.Result.FAILED, report.parts.single().result)
        assertEquals("VOICE_CALL_AUDIO_RELEASE_FAILED", report.parts.single().evidenceCode)
    }

    @Test
    fun earlierPageFailureUsesCallPartAndFiniteEvidence() {
        val report = failedWechatCallDeliveryReport(
            WechatActionType.START_VOICE_CALL,
            "CALL_CHOICE_PAGE_NOT_CONFIRMED",
        )

        assertEquals(ChannelResult.FAILED, report.result)
        assertEquals(ChannelPartResult.Part.CALL, report.parts.single().part)
        assertEquals(ChannelPartResult.Result.FAILED, report.parts.single().result)
        assertEquals("CALL_CHOICE_PAGE_NOT_CONFIRMED", report.parts.single().evidenceCode)
    }

    private fun transition(): WechatDirectChatTransitionRequest =
        WechatDirectChatTransitionRequest(
            planId = PLAN_ID,
            action = WechatActionType.START_VOICE_CALL,
            expectedPageType = WechatPageType.VOICE_CALL_CONFIRMATION,
            capability = capability(),
            openedAt = now,
            expiresAt = now.plusSeconds(3),
        )

    private fun request(): WechatCallChoiceActionRequest = WechatCallChoiceActionRequest(
        planId = PLAN_ID,
        action = WechatActionType.START_VOICE_CALL,
        expectedPageType = WechatPageType.VOICE_CALL_CONFIRMATION,
        capability = capability(),
        openedAt = now,
        expiresAt = now.plusSeconds(2),
    )

    private fun sample(): WechatPageStructureSample = WechatPageStructureSample(
        signatureSha256 = PAGE_SIGNATURE,
        nodeCount = 8,
        capturedAt = now.plusNanos(1),
    )

    private fun capability(): WechatCapabilitySnapshot = WechatCapabilitySnapshot(
        remotelyEnabled = true,
        signedRulesTrusted = true,
        combinationApproved = true,
        packageName = WECHAT_PACKAGE,
        wechatVersion = "8.0.76",
        ruleVersion = "wechat-rule-v1",
        locatorVersion = "wechat-contact-profile-v1",
        compatibleMinimumRuleVersions = setOf("wechat-rule-v1"),
        allowedActions = setOf(WechatActionType.START_VOICE_CALL),
        allowedPageTypes = mapOf(
            WechatActionType.START_VOICE_CALL to setOf(
                WechatPageType.CONTACT_PROFILE,
                WechatPageType.VOICE_CALL_CONFIRMATION,
            ),
        ),
        allowedPageSignatures = mapOf(
            WechatActionType.START_VOICE_CALL to setOf(PAGE_SIGNATURE),
        ),
        appBuildSha256 = "a".repeat(64),
        signingCertificateSha256 = "b".repeat(64),
        deviceManufacturer = "test-manufacturer",
        deviceModel = "test-model",
        androidSdkInt = 35,
        allowedPageSignaturesByType = mapOf(
            WechatActionType.START_VOICE_CALL to mapOf(
                WechatPageType.VOICE_CALL_CONFIRMATION to setOf(PAGE_SIGNATURE),
            ),
        ),
    )

    private companion object {
        const val PLAN_ID = "wp_call_plan"
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val PAGE_SIGNATURE =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
