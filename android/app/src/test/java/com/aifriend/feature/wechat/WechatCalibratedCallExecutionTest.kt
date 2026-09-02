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

class WechatCalibratedCallExecutionTest {
    private val now: OffsetDateTime = OffsetDateTime.parse("2026-09-01T00:00:00Z")

    @Test
    fun voiceCallRunsSevenCalibratedActionsExactlyOnceAndClearsClipboard() = runTest {
        val broker = WechatCalibratedCallExecutionBroker()
        val profile = profile()
        assertTrue(broker.arm(plan(WechatActionType.START_VOICE_CALL), profile, VERSION, now))
        val port = FakePort(profile)

        val outcome = WechatCalibratedCallExecutionExecutor(broker).execute(
            WechatSemanticCallContract.WECHAT_PACKAGE,
            VERSION,
            now,
            port,
        )

        assertEquals(WechatSemanticCallExecutionStatus.HANDED_TO_WECHAT, outcome?.status)
        assertEquals(
            listOf(
                "tap:HOME_SEARCH",
                "tap:GLOBAL_SEARCH_INPUT",
                "long:GLOBAL_SEARCH_INPUT",
                "tap:GLOBAL_SEARCH_PASTE",
                "tap:SEARCH_RESULT",
                "tap:CONTACT_PROFILE_CALL_ENTRY",
                "audio",
                "tap:CALL_CHOICE_VOICE",
            ),
            port.actions,
        )
        assertEquals(1, port.clipboardWrites)
        assertTrue(port.clipboardClears >= 2)
        assertFalse(broker.isPending())
        assertNull(
            WechatCalibratedCallExecutionExecutor(broker).execute(
                WechatSemanticCallContract.WECHAT_PACKAGE,
                VERSION,
                now,
                port,
            ),
        )
    }

    @Test
    fun videoCallUsesOnlyVideoChoicePoint() = runTest {
        val broker = WechatCalibratedCallExecutionBroker()
        val profile = profile()
        assertTrue(broker.arm(plan(WechatActionType.START_VIDEO_CALL), profile, VERSION, now))
        val port = FakePort(profile)

        val outcome = WechatCalibratedCallExecutionExecutor(broker).execute(
            WechatSemanticCallContract.WECHAT_PACKAGE,
            VERSION,
            now,
            port,
        )

        assertEquals(WechatSemanticCallExecutionStatus.HANDED_TO_WECHAT, outcome?.status)
        assertTrue(port.actions.contains("tap:CALL_CHOICE_VIDEO"))
        assertFalse(port.actions.contains("tap:CALL_CHOICE_VOICE"))
    }

    @Test
    fun changedDisplayFingerprintStopsBeforeAnyLaterStepWithoutRetry() = runTest {
        val broker = WechatCalibratedCallExecutionBroker()
        val profile = profile()
        assertTrue(broker.arm(plan(WechatActionType.START_VOICE_CALL), profile, VERSION, now))
        val port = FakePort(profile).apply {
            afterWait = { fingerprint = profile.key.copy(fontScalePermille = 1_100) }
        }

        val outcome = WechatCalibratedCallExecutionExecutor(broker).execute(
            WechatSemanticCallContract.WECHAT_PACKAGE,
            VERSION,
            now,
            port,
        )

        assertEquals(
            WechatSemanticCallExecutionStatus.CALIBRATED_PROFILE_CHANGED,
            outcome?.status,
        )
        assertEquals(listOf("tap:HOME_SEARCH"), port.actions)
        assertEquals(0, port.clipboardWrites)
        assertTrue(port.clipboardClears >= 1)
    }

    @Test
    fun clearingBrokerDuringSequenceStopsRemainingGestures() = runTest {
        val broker = WechatCalibratedCallExecutionBroker()
        val profile = profile()
        assertTrue(broker.arm(plan(WechatActionType.START_VOICE_CALL), profile, VERSION, now))
        val port = FakePort(profile).apply { afterWait = broker::clear }

        val outcome = WechatCalibratedCallExecutionExecutor(broker).execute(
            WechatSemanticCallContract.WECHAT_PACKAGE,
            VERSION,
            now,
            port,
        )

        assertEquals(WechatSemanticCallExecutionStatus.INTERRUPTED, outcome?.status)
        assertEquals(listOf("tap:HOME_SEARCH"), port.actions)
        assertEquals(0, port.clipboardWrites)
    }

    @Test
    fun rejectedGestureIsAttemptedOnceAndStopsChain() = runTest {
        val broker = WechatCalibratedCallExecutionBroker()
        val profile = profile()
        assertTrue(broker.arm(plan(WechatActionType.START_VOICE_CALL), profile, VERSION, now))
        val port = FakePort(profile).apply { rejectedActionNumber = 3 }

        val outcome = WechatCalibratedCallExecutionExecutor(broker).execute(
            WechatSemanticCallContract.WECHAT_PACKAGE,
            VERSION,
            now,
            port,
        )

        assertEquals(
            WechatSemanticCallExecutionStatus.CALIBRATED_GESTURE_REJECTED,
            outcome?.status,
        )
        assertEquals(
            listOf(
                "tap:HOME_SEARCH",
                "tap:GLOBAL_SEARCH_INPUT",
                "long:GLOBAL_SEARCH_INPUT",
            ),
            port.actions,
        )
        assertEquals(1, port.clipboardWrites)
        assertTrue(port.clipboardClears >= 1)
    }

    @Test
    fun unavailableClipboardStopsBeforeLongPress() = runTest {
        val broker = WechatCalibratedCallExecutionBroker()
        val profile = profile()
        assertTrue(broker.arm(plan(WechatActionType.START_VOICE_CALL), profile, VERSION, now))
        val port = FakePort(profile).apply { clipboardAvailable = false }

        val outcome = WechatCalibratedCallExecutionExecutor(broker).execute(
            WechatSemanticCallContract.WECHAT_PACKAGE,
            VERSION,
            now,
            port,
        )

        assertEquals(
            WechatSemanticCallExecutionStatus.CALIBRATED_CLIPBOARD_UNAVAILABLE,
            outcome?.status,
        )
        assertEquals(
            listOf("tap:HOME_SEARCH", "tap:GLOBAL_SEARCH_INPUT"),
            port.actions,
        )
    }

    @Test
    fun audioReleaseFailureNeverTouchesVoiceOrVideoChoice() = runTest {
        val broker = WechatCalibratedCallExecutionBroker()
        val profile = profile()
        assertTrue(broker.arm(plan(WechatActionType.START_VIDEO_CALL), profile, VERSION, now))
        val port = FakePort(profile).apply { audioAvailable = false }

        val outcome = WechatCalibratedCallExecutionExecutor(broker).execute(
            WechatSemanticCallContract.WECHAT_PACKAGE,
            VERSION,
            now,
            port,
        )

        assertEquals(WechatSemanticCallExecutionStatus.AUDIO_RELEASE_FAILED, outcome?.status)
        assertTrue(port.actions.last() == "audio")
        assertFalse(port.actions.any { it.contains("CALL_CHOICE_") })
    }

    @Test
    fun wrongPackageAndChangedVersionAreConsumedWithoutAnyGesture() {
        val profile = profile()
        val wrongPackageBroker = WechatCalibratedCallExecutionBroker()
        assertTrue(
            wrongPackageBroker.arm(
                plan(WechatActionType.START_VOICE_CALL),
                profile,
                VERSION,
                now,
            ),
        )
        val wrongPackage = wrongPackageBroker.take("other.package", VERSION, now)
        assertTrue(wrongPackage is WechatCalibratedCallTakeResult.Rejected)
        assertEquals(
            WechatSemanticCallExecutionStatus.PACKAGE_MISMATCH,
            (wrongPackage as WechatCalibratedCallTakeResult.Rejected).outcome.status,
        )
        assertFalse(wrongPackageBroker.isPending())

        val changedVersionBroker = WechatCalibratedCallExecutionBroker()
        assertTrue(
            changedVersionBroker.arm(
                plan(WechatActionType.START_VIDEO_CALL),
                profile,
                VERSION,
                now,
            ),
        )
        val changedVersion = changedVersionBroker.take(
            WechatSemanticCallContract.WECHAT_PACKAGE,
            "8.0.changed",
            now,
        )
        assertTrue(changedVersion is WechatCalibratedCallTakeResult.Rejected)
        assertEquals(
            WechatSemanticCallExecutionStatus.WECHAT_VERSION_CHANGED,
            (changedVersion as WechatCalibratedCallTakeResult.Rejected).outcome.status,
        )
        assertFalse(changedVersionBroker.isPending())
    }

    @Test
    fun executionWindowExpiresWithoutLeasingOrRetrying() {
        val broker = WechatCalibratedCallExecutionBroker()
        val profile = profile()
        assertTrue(broker.arm(plan(WechatActionType.START_VOICE_CALL), profile, VERSION, now))

        val result = broker.take(
            WechatSemanticCallContract.WECHAT_PACKAGE,
            VERSION,
            now.plusSeconds(25),
        )

        assertTrue(result is WechatCalibratedCallTakeResult.Rejected)
        assertEquals(
            WechatSemanticCallExecutionStatus.WINDOW_EXPIRED,
            (result as WechatCalibratedCallTakeResult.Rejected).outcome.status,
        )
        assertFalse(broker.isPending())
    }

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
            points = WechatCalibrationPurpose.CALL.targets.associateWith { target ->
                WechatNormalizedCalibrationPoint(
                    xMillionths = 100_000 + target.ordinal * 100_000,
                    yMillionths = 150_000 + target.ordinal * 100_000,
                )
            },
            updatedAtEpochMillis = 1L,
        )
    }

    private fun plan(action: WechatActionType): WechatActionPlan {
        val expiresAt = now.plusSeconds(30)
        return WechatActionPlan(
            planId = "11111111-1111-1111-1111-111111111111",
            action = action,
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
            audioObjectId = null,
        )
    }

    private class FakePort(
        private val profile: WechatCalibrationProfile,
    ) : WechatCalibratedCallUiPort {
        val actions = mutableListOf<String>()
        var clipboardWrites = 0
        var clipboardClears = 0
        var fingerprint: WechatCalibrationProfileKey? = profile.key
        var afterWait: (() -> Unit)? = null
        var rejectedActionNumber: Int? = null
        var clipboardAvailable = true
        var audioAvailable = true
        private var currentTime = OffsetDateTime.parse("2026-09-01T00:00:00Z")
        private val targetsByPoint = profile.points.map { (target, point) ->
            point.toPixels(
                profile.key.displayWidthPixels,
                profile.key.displayHeightPixels,
            ) to target
        }.toMap()

        override fun currentTime(): OffsetDateTime = currentTime

        override fun currentFingerprint(): WechatCalibrationProfileKey? = fingerprint

        override fun isWechatForeground(): Boolean = true

        override suspend fun tap(point: WechatCalibrationPixelPoint): Boolean {
            actions += "tap:${targetsByPoint.getValue(point).name}"
            return actions.size != rejectedActionNumber
        }

        override suspend fun longPress(point: WechatCalibrationPixelPoint): Boolean {
            actions += "long:${targetsByPoint.getValue(point).name}"
            return actions.size != rejectedActionNumber
        }

        override fun setSensitiveSearchClipboard(value: CharArray): Boolean {
            clipboardWrites += 1
            return clipboardAvailable && value.contentEquals("wxid_demo123".toCharArray())
        }

        override fun clearSearchClipboard() {
            clipboardClears += 1
        }

        override suspend fun waitForUi(duration: Duration) {
            currentTime = currentTime.plus(duration)
            afterWait?.also { afterWait = null }?.invoke()
        }

        override suspend fun releaseAudioBeforeCall(): Boolean {
            actions += "audio"
            return audioAvailable
        }
    }

    private companion object {
        const val VERSION = "8.0.76"
    }
}
