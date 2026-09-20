package com.aifriend.feature.wechat

import com.aifriend.contract.model.WechatActionPlan
import com.aifriend.contract.model.WechatActionType
import com.aifriend.contract.model.WechatTargetLocatorProof
import java.time.Duration
import java.time.OffsetDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatCalibratedMessageDeliveryTest {
    private val now = OffsetDateTime.parse("2026-09-01T00:00:00Z")

    @Test
    fun `message selector searches exact locator and confirms once`() = runTest {
        val broker = WechatCalibratedMessageSelectionBroker()
        val profile = profile()
        val armed = requireNotNull(broker.arm(plan(), "transaction-1", profile, VERSION, now))
        val request = requireNotNull(
            broker.take(WechatSemanticCallContract.WECHAT_PACKAGE, now, SELECTOR),
        )
        val port = FakePort(profile)

        val success = WechatCalibratedMessageSelectionExecutor().execute(
            request = request,
            packageName = WechatSemanticCallContract.WECHAT_PACKAGE,
            currentWechatVersion = VERSION,
            uiPort = port,
        )
        broker.finish(request, success)

        assertTrue(success)
        assertEquals(
            listOf(
                "tap:SHARE_SEARCH_ENTRY",
                "tap:SHARE_SEARCH_INPUT",
                "long:SHARE_SEARCH_INPUT",
                "tap:SHARE_SEARCH_PASTE",
                "tap:SHARE_SEARCH_RESULT",
                "tap:SHARE_SEND_CONFIRM",
            ),
            port.actions,
        )
        assertEquals(1, port.clipboardWrites)
        assertTrue(port.clipboardClears >= 1)
        assertTrue(armed.completion.await())
        assertTrue(request.targetSearchLocator.all { it == '\u0000' })
        assertFalse(broker.isPending())
    }

    @Test
    fun `rejected gesture stops without retrying or confirming`() = runTest {
        val profile = profile()
        val request = request(profile)
        val port = FakePort(profile).apply { rejectedActionNumber = 3 }

        val success = WechatCalibratedMessageSelectionExecutor().execute(
            request = request,
            packageName = WechatSemanticCallContract.WECHAT_PACKAGE,
            currentWechatVersion = VERSION,
            uiPort = port,
        )

        assertFalse(success)
        assertEquals(
            listOf(
                "tap:SHARE_SEARCH_ENTRY",
                "tap:SHARE_SEARCH_INPUT",
                "long:SHARE_SEARCH_INPUT",
            ),
            port.actions,
        )
        assertEquals(1, port.clipboardWrites)
    }

    @Test
    fun `wrong package cannot lease request and expiry clears locator`() = runTest {
        val broker = WechatCalibratedMessageSelectionBroker()
        val profile = profile()
        val armed = requireNotNull(broker.arm(plan(), "transaction-2", profile, VERSION, now))

        assertNull(broker.take("other.package", now, SELECTOR))
        assertTrue(broker.isPending())
        assertNull(
            broker.take(
                WechatSemanticCallContract.WECHAT_PACKAGE,
                now.plusSeconds(31),
                SELECTOR,
            ),
        )

        assertFalse(armed.completion.await())
        assertTrue(armed.targetSearchLocator.all { it == '\u0000' })
        assertFalse(broker.isPending())
    }

    @Test
    fun `sdk callback is consumed once by exact transaction`() = runTest {
        val broker = WechatMessageOpenSdkCallbackBroker()
        val callback = requireNotNull(broker.register("transaction-3"))

        broker.complete("other", 0)
        assertFalse(callback.isCompleted)
        broker.complete("transaction-3", 0)
        broker.complete("transaction-3", -1)

        assertEquals(0, callback.await())
    }

    @Test
    fun `direct input skips long press and pasted menu`() = runTest {
        val profile = profile()
        val port = FakePort(profile).apply { directInput = true }
        assertTrue(WechatCalibratedMessageSelectionExecutor().execute(
            request(profile), WechatSemanticCallContract.WECHAT_PACKAGE, VERSION, port,
        ))
        assertEquals(0, port.clipboardWrites)
        assertFalse(port.actions.any { it.startsWith("long:") || it.contains("PASTE") })
        assertEquals(1, port.actions.count { it == "tap:SHARE_SEND_CONFIRM" })
    }

    @Test
    fun `wechat returning to app after send tap still waits for sdk callback`() = runTest {
        val profile = profile()
        val port = FakePort(profile).apply { leavesAfterConfirm = true }
        assertTrue(WechatCalibratedMessageSelectionExecutor().execute(
            request(profile), WechatSemanticCallContract.WECHAT_PACKAGE, VERSION, port,
        ))
        assertFalse(port.isWechatForeground())
        assertEquals(1, port.actions.count { it == "tap:SHARE_SEND_CONFIRM" })
    }

    @Test
    fun `transparent sdk window and launcher cannot consume pending selection`() = runTest {
        val broker = WechatCalibratedMessageSelectionBroker()
        val armed = requireNotNull(broker.arm(plan(), "window-test", profile(), VERSION, now))
        for (window in listOf(null, "android.widget.EditText",
            "com.tencent.mm.ui.LauncherUI", "com.tencent.mm.ui.halfscreen.HalfScreenTransparentActivity")) {
            assertNull(broker.take(WechatSemanticCallContract.WECHAT_PACKAGE, now, window))
            assertTrue(broker.isPending())
            assertFalse(armed.completion.isCompleted)
        }
        assertEquals(armed, broker.take(WechatSemanticCallContract.WECHAT_PACKAGE, now.plusSeconds(2), SELECTOR))
        assertNull(broker.take(WechatSemanticCallContract.WECHAT_PACKAGE, now.plusSeconds(2), SELECTOR))
        broker.finish(armed, false)
    }

    @Test
    fun `late selector cannot revive expired sdk request`() = runTest {
        val broker = WechatCalibratedMessageSelectionBroker()
        val armed = requireNotNull(broker.arm(plan(), "late-window", profile(), VERSION, now))
        assertNull(broker.take(WechatSemanticCallContract.WECHAT_PACKAGE, now.plusSeconds(31), SELECTOR))
        assertFalse(armed.completion.await())
        assertTrue(armed.targetSearchLocator.all { it == '\u0000' })
    }

    @Test
    fun `unverified query or pinned result page cannot select or send`() = runTest {
        val profile = profile()
        val port = FakePort(profile).apply { resultVerified = false }
        assertFalse(WechatCalibratedMessageSelectionExecutor().execute(
            request(profile), WechatSemanticCallContract.WECHAT_PACKAGE, VERSION, port,
        ))
        assertFalse(port.actions.contains("tap:SHARE_SEARCH_RESULT"))
        assertFalse(port.actions.contains("tap:SHARE_SEND_CONFIRM"))
        assertTrue(port.clipboardClears > 0)
    }

    @Test
    fun `missing unique send button cannot submit sharing`() = runTest {
        val profile = profile()
        val port = FakePort(profile).apply { sendVerified = false }
        assertFalse(WechatCalibratedMessageSelectionExecutor().execute(
            request(profile), WechatSemanticCallContract.WECHAT_PACKAGE, VERSION, port,
        ))
        assertTrue(port.actions.contains("tap:SHARE_SEARCH_RESULT"))
        assertFalse(port.actions.contains("tap:SHARE_SEND_CONFIRM"))
    }

    @Test
    fun `dialog with background cancel reaches exactly one final tap`() = runTest {
        val profile = profile()
        val point = profile.points.getValue(WechatCalibrationTarget.SHARE_SEND_CONFIRM)
            .toPixels(profile.key.displayWidthPixels, profile.key.displayHeightPixels)
        val send = WechatShareButtonNode(WechatShareButtonNode.Label.SEND,
            point.x - 30, point.y - 30, point.x + 30, point.y + 30, true)
        val cancel = send.copy(label = WechatShareButtonNode.Label.CANCEL,
            left = point.x - 200, right = point.x - 100)
        val header = cancel.copy(top = 150, bottom = 200)
        val port = FakePort(profile).apply { sendNodes = listOf(send, cancel, header) }

        assertTrue(WechatCalibratedMessageSelectionExecutor().execute(
            request(profile), WechatSemanticCallContract.WECHAT_PACKAGE, VERSION, port))
        assertEquals(1, port.actions.count { it == "tap:SHARE_SEARCH_RESULT" })
        assertEquals(1, port.actions.count { it == "tap:SHARE_SEND_CONFIRM" })
    }

    @Test
    fun `ambiguous dialog buttons never trigger final tap`() = runTest {
        val profile = profile()
        val point = profile.points.getValue(WechatCalibrationTarget.SHARE_SEND_CONFIRM)
            .toPixels(profile.key.displayWidthPixels, profile.key.displayHeightPixels)
        val send = WechatShareButtonNode(WechatShareButtonNode.Label.SEND,
            point.x - 30, point.y - 30, point.x + 30, point.y + 30, true)
        val cancel = send.copy(label = WechatShareButtonNode.Label.CANCEL,
            left = point.x - 200, right = point.x - 100)
        for (nodes in listOf(listOf(send, cancel, cancel), listOf(send, send, cancel),
            listOf(send.copy(clickable = false), cancel))) {
            val port = FakePort(profile).apply { sendNodes = nodes }
            assertFalse(WechatCalibratedMessageSelectionExecutor().execute(
                request(profile), WechatSemanticCallContract.WECHAT_PACKAGE, VERSION, port))
            assertEquals(0, port.actions.count { it == "tap:SHARE_SEND_CONFIRM" })
        }
    }

    private fun request(profile: WechatCalibrationProfile) =
        WechatCalibratedMessageSelectionRequest(
            planId = "11111111-1111-1111-1111-111111111111",
            transaction = "transaction-test",
            profile = profile,
            targetSearchLocator = "wxid_demo123".toCharArray(),
            wechatVersion = VERSION,
            openedAt = now,
            expiresAt = now.plusSeconds(30),
            completion = kotlinx.coroutines.CompletableDeferred(),
        )

    private fun profile(): WechatCalibrationProfile {
        val key = WechatCalibrationProfileKey(
            manufacturer = "vivo",
            model = "V2536A",
            androidSdkInt = 36,
            displayWidthPixels = 1080,
            displayHeightPixels = 2400,
            densityDpi = 440,
            fontScalePermille = 1_000,
            orientation = WechatCalibrationOrientation.PORTRAIT,
            wechatVersion = VERSION,
        )
        return WechatCalibrationProfile(
            key = key,
            points = WechatCalibrationPurpose.MESSAGE.targets.associateWith { target ->
                WechatNormalizedCalibrationPoint(
                    xMillionths = 100_000 + target.ordinal * 50_000,
                    yMillionths = 150_000 + target.ordinal * 50_000,
                )
            },
            updatedAtEpochMillis = 1L,
        )
    }

    private fun plan(): WechatActionPlan {
        val expiresAt = now.plusSeconds(30)
        return WechatActionPlan(
            planId = "11111111-1111-1111-1111-111111111111",
            action = WechatActionType.SEND_AUDIO_AND_TEXT,
            contactId = "22222222-2222-2222-2222-222222222222",
            summaryHash = "a".repeat(64),
            minimumRuleVersion = WechatSemanticCallContract.RULE_VERSION,
            expiresAt = expiresAt,
            targetSearchLocator = "wxid_demo123",
            targetLocatorProof = WechatTargetLocatorProof(
                proofVersion = WechatTargetLocatorProof.ProofVersion.WECHAT_LOCATOR_PROOF_V1,
                keyId = "test-key",
                contactVersion = 1,
                wechatVersion = VERSION,
                locatorVersion = WechatSemanticCallContract.LOCATOR_VERSION,
                salt = "0".repeat(32),
                targetLocatorSha256 = "b".repeat(64),
                issuedAt = now.minusSeconds(1),
                expiresAt = expiresAt,
                signature = "A".repeat(86),
            ),
            audioObjectId = "33333333-3333-3333-3333-333333333333",
        )
    }

    private class FakePort(
        private val profile: WechatCalibrationProfile,
    ) : WechatCalibratedCallUiPort {
        val actions = mutableListOf<String>()
        var clipboardWrites = 0
        var clipboardClears = 0
        var rejectedActionNumber: Int? = null
        var directInput = false
        var leavesAfterConfirm = false
        var resultVerified = true
        var sendVerified = true
        var sendNodes: List<WechatShareButtonNode>? = null
        private var foreground = true
        private var currentTime = OffsetDateTime.parse("2026-09-01T00:00:00Z")
        private val targetsByPoint = profile.points.map { (target, point) ->
            point.toPixels(
                profile.key.displayWidthPixels,
                profile.key.displayHeightPixels,
            ) to target
        }.toMap()

        override fun currentTime(): OffsetDateTime = currentTime

        override fun currentFingerprint(): WechatCalibrationProfileKey = profile.key

        override fun isWechatForeground(): Boolean = foreground

        override fun setSearchText(value: CharArray): Boolean = directInput

        override suspend fun verifyMessageSearchResult(value: CharArray, point: WechatCalibrationPixelPoint): Boolean = resultVerified

        override suspend fun verifyMessageSendButton(point: WechatCalibrationPixelPoint): Boolean =
            sendNodes?.let { WechatShareSendNodeRule.verify(it, point) == WechatShareSendNodeRule.Decision.VERIFIED }
                ?: sendVerified

        override suspend fun tap(point: WechatCalibrationPixelPoint): Boolean {
            actions += "tap:${targetsByPoint.getValue(point).name}"
            if (leavesAfterConfirm && targetsByPoint.getValue(point) == WechatCalibrationTarget.SHARE_SEND_CONFIRM) {
                foreground = false
            }
            return actions.size != rejectedActionNumber
        }

        override suspend fun longPress(point: WechatCalibrationPixelPoint): Boolean {
            actions += "long:${targetsByPoint.getValue(point).name}"
            return actions.size != rejectedActionNumber
        }

        override fun setSensitiveSearchClipboard(value: CharArray): Boolean {
            clipboardWrites += 1
            return value.contentEquals("wxid_demo123".toCharArray())
        }

        override fun clearSearchClipboard() {
            clipboardClears += 1
        }

        override suspend fun waitForUi(duration: Duration) {
            currentTime = currentTime.plus(duration)
        }

        override suspend fun releaseAudioBeforeCall(): Boolean = true
    }

    private companion object {
        const val SELECTOR = "com.tencent.mm.ui.transmit.SelectConversationUI"
        const val VERSION = "8.0.76"
    }
}
