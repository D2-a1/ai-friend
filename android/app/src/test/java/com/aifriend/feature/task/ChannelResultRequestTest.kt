package com.aifriend.feature.task

import com.aifriend.contract.model.ChannelPartResult
import com.aifriend.contract.model.ChannelResult
import com.aifriend.contract.model.WechatActionPlan
import com.aifriend.contract.model.WechatActionType
import com.aifriend.contract.model.WechatTargetLocatorProof
import com.aifriend.feature.wechat.unsupportedWechatMessageDeliveryReport
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** 既有渠道结果接口的请求映射测试。 */
class ChannelResultRequestTest {
    @Test
    fun mapsCurrentPlanAndFinitePartsWithoutChangingEvidence() {
        val occurredAt = OffsetDateTime.parse("2026-08-30T11:00:00Z")
        val part = ChannelPartResult(
            part = ChannelPartResult.Part.AUDIO,
            result = ChannelPartResult.Result.HANDED_TO_WECHAT,
        )

        val request = channelResultRequest(
            plan = plan(),
            result = ChannelResult.HANDED_TO_WECHAT,
            parts = listOf(part),
            occurredAt = occurredAt,
        )

        assertEquals("plan-1", request.planId)
        assertEquals("summary-hash", request.summaryHash)
        assertEquals("wechat-rule-v1", request.ruleVersion)
        assertEquals(ChannelResult.HANDED_TO_WECHAT, request.result)
        assertEquals(occurredAt, request.occurredAt)
        assertEquals(listOf(part), request.parts)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyPartList() {
        channelResultRequest(
            plan = plan(),
            result = ChannelResult.FAILED,
            parts = emptyList(),
            occurredAt = OffsetDateTime.parse("2026-08-30T11:00:00Z"),
        )
    }

    @Test
    fun mapsSpecifiedContactIdentityFailureWithoutAddingTextPart() {
        val occurredAt = OffsetDateTime.parse("2026-08-30T11:00:00Z")
        val report = unsupportedWechatMessageDeliveryReport()

        val request = channelResultRequest(
            plan = plan(),
            result = report.result,
            parts = report.parts,
            occurredAt = occurredAt,
        )

        assertEquals(ChannelResult.UNSUPPORTED, request.result)
        val parts = requireNotNull(request.parts)
        assertEquals(1, parts.size)
        assertEquals(ChannelPartResult.Part.AUDIO, parts.single().part)
        assertEquals(ChannelPartResult.Result.UNSUPPORTED, parts.single().result)
    }

    private fun plan(): WechatActionPlan = WechatActionPlan(
        planId = "plan-1",
        action = WechatActionType.SEND_AUDIO_AND_TEXT,
        contactId = "contact-1",
        summaryHash = "summary-hash",
        minimumRuleVersion = "wechat-rule-v1",
        expiresAt = OffsetDateTime.parse("2026-08-30T11:01:00Z"),
        targetSearchLocator = "wxid_demo123",
        targetLocatorProof = WechatTargetLocatorProof(
            proofVersion = WechatTargetLocatorProof.ProofVersion.WECHAT_LOCATOR_PROOF_V1,
            keyId = "key-1",
            contactVersion = 1,
            wechatVersion = "8.0.76",
            locatorVersion = "wechat-contact-profile-v1",
            salt = "00112233445566778899aabbccddeeff",
            targetLocatorSha256 = "a".repeat(64),
            issuedAt = OffsetDateTime.parse("2026-08-30T10:59:00Z"),
            expiresAt = OffsetDateTime.parse("2026-08-30T11:01:00Z"),
            signature = "signature",
        ),
        audioObjectId = "audio-1",
    )
}
