package com.aifriend.feature.wechat

import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatLocalContactVerificationTest {
    private val now = OffsetDateTime.parse("2026-08-30T08:00:00Z")

    @Test
    fun oneMatchingProfileObservationProducesMinimalEvidence() {
        val session = readySession()
        session.start()

        assertTrue(session.begin(WECHAT_VERSION, now))
        val locator = "wxid_family_123".toCharArray()
        session.publish(sample(locator, now.plusSeconds(1)), WECHAT_VERSION, now.plusSeconds(1))
        assertTrue(locator.all { it == '\u0000' })

        assertTrue(session.state.value.ready)
        assertEquals(1, session.state.value.completedObservations)
        val evidence = session.buildEvidence(expectedContactVersion = 5)
        assertEquals("wxid_family_123", evidence?.stableLocator)
        assertEquals(1, evidence?.locatorObservationCount)
        assertEquals(true, evidence?.friendConfirmed)
        assertEquals(true, evidence?.locatorUnique)
        assertEquals(WechatLocalVerificationSession.RULE_VERSION, evidence?.ruleVersion)
        assertFalse(evidence.toString().contains("wxid_family_123"))
    }

    @Test
    fun completedSingleConfirmationCannotStartAnotherObservation() {
        val session = readySession()
        session.start()
        assertTrue(session.begin(WECHAT_VERSION, now))
        session.publish(sample("wxid_first_123".toCharArray(), now.plusSeconds(1)), WECHAT_VERSION, now.plusSeconds(1))

        assertFalse(session.begin(WECHAT_VERSION, now.plusSeconds(2)))
        assertEquals(1, session.state.value.completedObservations)
        assertTrue(session.state.value.ready)
        assertEquals("wxid_first_123", session.buildEvidence(5)?.stableLocator)
    }

    @Test
    fun profileWithoutUniqueLocatorAndFriendActionNeverAdvances() {
        val session = readySession()
        session.start()
        assertTrue(session.begin(WECHAT_VERSION, now))
        val first = "wxid_family_123".toCharArray()
        val second = "wxid_family_456".toCharArray()
        val invalid = WechatLocalVerificationSample(
            stableLocatorCandidates = listOf(first, second),
            friendActionMatchCount = 0,
            pageSignatureSha256 = SIGNATURE,
            nodeCount = 2,
            capturedAt = now.plusSeconds(1),
        )

        session.publish(invalid, WECHAT_VERSION, now.plusSeconds(1))

        assertEquals(0, session.state.value.completedObservations)
        assertTrue(session.state.value.awaiting)
        assertTrue(session.state.value.message.contains("微信号"))
        assertTrue(first.all { it == '\u0000' })
        assertTrue(second.all { it == '\u0000' })
    }

    @Test
    fun matchedNodeShapeDigestIsStableAndContainsNoLocatorText() {
        val shapes = listOf(
            WechatLocalVerificationNodeShape(
                role = WechatLocalVerificationNodeShape.Role.LOCATOR,
                className = "android.widget.TextView",
                viewIdResourceName = "com.tencent.mm:id/contact_locator",
                childCount = 0,
            ),
            WechatLocalVerificationNodeShape(
                role = WechatLocalVerificationNodeShape.Role.FRIEND_ACTION,
                className = "android.widget.Button",
                viewIdResourceName = "com.tencent.mm:id/send_message",
                childCount = 0,
            ),
        )

        val first = WechatLocalVerificationShapeCanonicalizer.sha256(shapes)
        val second = WechatLocalVerificationShapeCanonicalizer.sha256(shapes)

        assertEquals(first, second)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
        assertFalse(first.contains("wxid_family_123"))
    }

    @Test
    fun debugCallProfileSampleUsesTheExactFormalNarrowPageDigest() {
        assertEquals(
            WechatLocalVerificationTextRule.contactProfileActionQuery(
                com.aifriend.contract.model.WechatActionType.START_VOICE_CALL,
            ),
            WechatLocalVerificationTextRule.contactProfileActionQuery(
                com.aifriend.contract.model.WechatActionType.START_VIDEO_CALL,
            ),
        )
        val shapes = listOf(
            WechatLocalVerificationNodeShape(
                role = WechatLocalVerificationNodeShape.Role.LOCATOR,
                className = "android.widget.TextView",
                viewIdResourceName = "com.tencent.mm:id/contact_locator",
                childCount = 0,
            ),
            WechatLocalVerificationNodeShape(
                role = WechatLocalVerificationNodeShape.Role.CALL_ACTION,
                className = "android.widget.Button",
                viewIdResourceName = "com.tencent.mm:id/audio_video_call",
                childCount = 0,
            ),
        )

        val sample = WechatContactProfileCallRuleSampleFactory.build(
            shapes = shapes,
            locatorCandidateCount = 1,
            actionNodeMatchCount = 1,
            capturedAt = now,
        )

        assertEquals(
            WechatLocalVerificationShapeCanonicalizer.sha256(shapes),
            sample?.signatureSha256,
        )
        assertEquals(2, sample?.nodeCount)
        assertEquals(1, sample?.locatorSourceSha256s?.size)
    }

    @Test
    fun debugCallProfileSampleRejectsNonUniqueOrMessageActionShapes() {
        val messageShapes = listOf(
            WechatLocalVerificationNodeShape(
                role = WechatLocalVerificationNodeShape.Role.LOCATOR,
                className = "android.widget.TextView",
                viewIdResourceName = "locator",
                childCount = 0,
            ),
            WechatLocalVerificationNodeShape(
                role = WechatLocalVerificationNodeShape.Role.FRIEND_ACTION,
                className = "android.widget.Button",
                viewIdResourceName = "send_message",
                childCount = 0,
            ),
        )

        assertNull(
            WechatContactProfileCallRuleSampleFactory.build(
                shapes = messageShapes,
                locatorCandidateCount = 1,
                actionNodeMatchCount = 1,
                capturedAt = now,
            ),
        )
        assertNull(
            WechatContactProfileCallRuleSampleFactory.build(
                shapes = messageShapes,
                locatorCandidateCount = 2,
                actionNodeMatchCount = 1,
                capturedAt = now,
            ),
        )
    }

    @Test
    fun profileWithoutLocatorExplainsWhyObservationCannotAdvance() {
        val session = readySession()
        session.start()
        assertTrue(session.begin(WECHAT_VERSION, now))
        session.publish(
            WechatLocalVerificationSample(
                stableLocatorCandidates = emptyList(),
                friendActionMatchCount = 1,
                pageSignatureSha256 = SIGNATURE,
                nodeCount = 1,
                capturedAt = now.plusSeconds(1),
            ),
            WECHAT_VERSION,
            now.plusSeconds(1),
        )

        assertEquals(0, session.state.value.completedObservations)
        assertTrue(session.state.value.awaiting)
        assertTrue(session.state.value.message.contains("微信号"))
    }

    @Test
    fun profileWithoutFriendActionExplainsWhyObservationCannotAdvance() {
        val session = readySession()
        session.start()
        assertTrue(session.begin(WECHAT_VERSION, now))
        val locator = "wxid_family_123".toCharArray()
        session.publish(
            WechatLocalVerificationSample(
                stableLocatorCandidates = listOf(locator),
                friendActionMatchCount = 0,
                pageSignatureSha256 = SIGNATURE,
                nodeCount = 1,
                capturedAt = now.plusSeconds(1),
            ),
            WECHAT_VERSION,
            now.plusSeconds(1),
        )

        assertEquals(0, session.state.value.completedObservations)
        assertTrue(session.state.value.awaiting)
        assertTrue(session.state.value.message.contains("发消息"))
        assertTrue(locator.all { it == '\u0000' })
    }

    @Test
    fun strictTextRuleRejectsNamesPhonesAndNonFriendActions() {
        assertEquals(
            "wxid_family_123",
            WechatLocalVerificationTextRule.locator(" 微信号：wxid_family_123 ")?.concatToString(),
        )
        assertNull(WechatLocalVerificationTextRule.locator("昵称：wxid_family_123"))
        assertNull(WechatLocalVerificationTextRule.locator("微信号：13800138000"))
        assertNull(WechatLocalVerificationTextRule.locator("微信号：中文名字"))
        assertEquals(
            "wxid_description_123",
            WechatLocalVerificationTextRule.locator(
                null,
                "微信号：wxid_description_123",
            )?.concatToString(),
        )
        val noBreakSpace = 0x00a0.toChar()
        val leftToRightMark = 0x200e.toChar()
        val narrowNoBreakSpace = 0x202f.toChar()
        assertEquals(
            "wxid_family_123",
            WechatLocalVerificationTextRule.locator(
                "$noBreakSpace 微信号：$leftToRightMark" +
                    "wxid_family_123$narrowNoBreakSpace",
            )?.concatToString(),
        )
        assertTrue(
            WechatLocalVerificationTextRule.isLocatorLabel(
                "$leftToRightMark 微信号：$noBreakSpace",
            ),
        )
        assertEquals(
            "wxid_family_123",
            WechatLocalVerificationTextRule.standaloneLocator(
                "$leftToRightMark wxid_family_123$narrowNoBreakSpace",
            )?.concatToString(),
        )
        assertNull(WechatLocalVerificationTextRule.standaloneLocator("13800138000"))
        assertNull(WechatLocalVerificationTextRule.standaloneLocator("Jack."))
        assertNull(WechatLocalVerificationTextRule.standaloneLocator("中文名字"))
        assertEquals(
            "wxid_description_123",
            WechatLocalVerificationTextRule.standaloneLocator(
                null,
                "wxid_description_123",
            )?.concatToString(),
        )
        assertTrue(WechatLocalVerificationTextRule.isFriendAction(" 发消息 "))
        assertTrue(
            WechatLocalVerificationTextRule.isFriendAction(
                "$noBreakSpace 发消息$leftToRightMark",
            ),
        )
        assertFalse(WechatLocalVerificationTextRule.isFriendAction("添加到通讯录"))
        assertTrue(
            WechatLocalVerificationTextRule.isContactProfileAction(
                " 音视频通话 ",
                com.aifriend.contract.model.WechatActionType.START_VOICE_CALL,
            ),
        )
        assertFalse(
            WechatLocalVerificationTextRule.isContactProfileAction(
                "发消息",
                com.aifriend.contract.model.WechatActionType.START_VIDEO_CALL,
            ),
        )
        assertTrue(
            WechatLocalVerificationTextRule.isContactProfileAction(
                null,
                "音视频通话",
                com.aifriend.contract.model.WechatActionType.START_VIDEO_CALL,
            ),
        )
    }

    @Test
    fun disconnectedAccessibilityServiceCannotEnterSilentWaitingState() {
        val session = WechatLocalVerificationSession()
        session.start()

        assertFalse(session.begin(WECHAT_VERSION, now))
        assertFalse(session.state.value.awaiting)
        assertFalse(session.state.value.accessibilityReady)
        assertTrue(session.state.value.message.contains("未连接"))
        assertNull(session.activeRequest("com.tencent.mm", now))
    }

    @Test
    fun connectedServiceCanBeginAndDisconnectEndsCurrentWindow() {
        val session = WechatLocalVerificationSession()
        session.updateAccessibilityReady(true)
        session.start()

        assertTrue(session.begin(WECHAT_VERSION, now))
        assertTrue(session.state.value.awaiting)
        session.updateAccessibilityReady(false)

        assertFalse(session.state.value.awaiting)
        assertFalse(session.state.value.accessibilityReady)
        assertTrue(session.state.value.message.contains("未连接"))
        assertNull(session.activeRequest("com.tencent.mm", now.plusSeconds(1)))
    }

    @Test
    fun receivedEventAndUnavailableRootProduceActionableState() {
        val session = readySession()
        session.start()
        assertTrue(session.begin(WECHAT_VERSION, now))

        session.markWechatEventReceived(now.plusSeconds(1))
        assertTrue(session.state.value.message.contains("已收到微信页面变化"))
        session.failCurrentRead("已收到微信页面变化，但系统没有提供页面内容。")

        assertFalse(session.state.value.awaiting)
        assertTrue(session.state.value.accessibilityReady)
        assertTrue(session.state.value.message.contains("没有提供页面内容"))
        assertNull(session.activeRequest("com.tencent.mm", now.plusSeconds(2)))
    }

    private fun readySession() = WechatLocalVerificationSession().apply {
        updateAccessibilityReady(true)
    }

    private fun sample(locator: CharArray, capturedAt: OffsetDateTime) =
        WechatLocalVerificationSample(
            stableLocatorCandidates = listOf(locator),
            friendActionMatchCount = 1,
            pageSignatureSha256 = SIGNATURE,
            nodeCount = 2,
            capturedAt = capturedAt,
        )

    private companion object {
        const val WECHAT_VERSION = "8.0.50"
        const val SIGNATURE =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
