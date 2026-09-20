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
    fun voiceCallUsesVerifiedInputProfileAndChoiceWithoutClipboard() = runTest {
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

        assertEquals(WechatSemanticCallExecutionStatus.AWAITING_CALL_STARTED, outcome?.status)
        assertEquals(
            listOf(
                "tap:HOME_SEARCH",
                "tap:GLOBAL_SEARCH_INPUT",
                "setText",
                "tap:SEARCH_RESULT",
                "tap:CHAT_INFO_MENU",
                "tap:CHAT_INFO_CONTACT_AVATAR",
                "readProfile",
                "tap:CONTACT_PROFILE_CALL_ENTRY",
                "readChoice",
                "audio",
                "tap:CALL_CHOICE_VOICE",
            ),
            port.actions,
        )
        assertEquals(0, port.clipboardWrites)
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

        assertEquals(WechatSemanticCallExecutionStatus.AWAITING_CALL_STARTED, outcome?.status)
        assertTrue(port.actions.contains("tap:CALL_CHOICE_VIDEO"))
        assertFalse(port.actions.contains("tap:CALL_CHOICE_VOICE"))
    }

    @Test
    fun missingActiveCallPageEvidenceStillPerformsChoiceAndHandsToWechat() = runTest {
        val broker = WechatCalibratedCallExecutionBroker()
        val profile = profile()
        assertTrue(
            broker.arm(
                plan(WechatActionType.START_VOICE_CALL),
                profile,
                VERSION,
                capability(WechatActionType.START_VOICE_CALL, includeActivePage = false),
                now,
            ),
        )
        val port = FakePort(profile)

        val outcome = WechatCalibratedCallExecutionExecutor(broker).execute(
            WechatSemanticCallContract.WECHAT_PACKAGE,
            VERSION,
            now,
            port,
        )

        assertEquals(WechatSemanticCallExecutionStatus.HANDED_TO_WECHAT, outcome?.status)
        assertEquals("tap:CALL_CHOICE_VOICE", port.actions.last())
        assertEquals(1, port.actions.count { it == "tap:CALL_CHOICE_VOICE" })
        assertFalse(broker.isPending())
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
        val port = FakePort(profile).apply {
            directSearchAvailable = false
            rejectedGestureNumber = 3
        }

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
                "setText",
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
        val port = FakePort(profile).apply {
            directSearchAvailable = false
            clipboardAvailable = false
        }

        val outcome = WechatCalibratedCallExecutionExecutor(broker).execute(
            WechatSemanticCallContract.WECHAT_PACKAGE,
            VERSION,
            now,
            port,
        )

        assertEquals(
            WechatSemanticCallExecutionStatus.CALIBRATED_SEARCH_INPUT_UNAVAILABLE,
            outcome?.status,
        )
        assertEquals(
            listOf("tap:HOME_SEARCH", "tap:GLOBAL_SEARCH_INPUT", "setText"),
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
    fun clipboardFallbackPastesBeforeSearchResultAndProfileStillGuardsTarget() = runTest {
        val broker = WechatCalibratedCallExecutionBroker()
        val profile = profile()
        assertTrue(broker.arm(plan(WechatActionType.START_VOICE_CALL), profile, VERSION, now))
        val port = FakePort(profile).apply { directSearchAvailable = false }

        val outcome = WechatCalibratedCallExecutionExecutor(broker).execute(
            WechatSemanticCallContract.WECHAT_PACKAGE,
            VERSION,
            now,
            port,
        )

        assertEquals(WechatSemanticCallExecutionStatus.AWAITING_CALL_STARTED, outcome?.status)
        assertTrue(port.actions.contains("long:GLOBAL_SEARCH_INPUT"))
        assertTrue(port.actions.contains("tap:GLOBAL_SEARCH_PASTE"))
        assertEquals(1, port.clipboardWrites)
    }

    @Test
    fun chatAvatarGestureFailureStopsBeforeProfileRead() = runTest {
        val broker = WechatCalibratedCallExecutionBroker()
        val profile = profile()
        assertTrue(broker.arm(plan(WechatActionType.START_VOICE_CALL), profile, VERSION, now))
        val port = FakePort(profile).apply { rejectedGestureNumber = 5 }

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
        assertEquals(1, port.actions.count { it == "tap:SEARCH_RESULT" })
        assertEquals(1, port.actions.count { it == "tap:CHAT_INFO_MENU" })
        assertEquals(1, port.actions.count { it == "tap:CHAT_INFO_CONTACT_AVATAR" })
        assertFalse(port.actions.contains("readProfile"))
        assertFalse(port.actions.contains("tap:CONTACT_PROFILE_CALL_ENTRY"))
        assertFalse(port.actions.contains("audio"))
    }
    @Test
    fun wrongProfileDigestStopsBeforeCallEntryAndAudioRelease() = runTest {
        val broker = WechatCalibratedCallExecutionBroker()
        val profile = profile()
        assertTrue(broker.arm(plan(WechatActionType.START_VOICE_CALL), profile, VERSION, now))
        val port = FakePort(profile).apply { profileDigest = "c".repeat(64) }

        val outcome = WechatCalibratedCallExecutionExecutor(broker).execute(
            WechatSemanticCallContract.WECHAT_PACKAGE,
            VERSION,
            now,
            port,
        )

        assertEquals(
            WechatSemanticCallExecutionStatus.PROFILE_TARGET_MISMATCH,
            outcome?.status,
        )
        assertFalse(port.actions.contains("tap:CONTACT_PROFILE_CALL_ENTRY"))
        assertFalse(port.actions.contains("audio"))
        assertFalse(port.actions.any { it.startsWith("tap:CALL_CHOICE_") })
    }

    @Test
    fun delayedProfileMayLoadWithinBoundedWindowWithoutRetappingSearchResult() = runTest {
        val broker = WechatCalibratedCallExecutionBroker()
        val profile = profile()
        assertTrue(broker.arm(plan(WechatActionType.START_VIDEO_CALL), profile, VERSION, now))
        val port = FakePort(profile).apply { profileUnavailableReads = 3 }

        val outcome = WechatCalibratedCallExecutionExecutor(broker).execute(
            WechatSemanticCallContract.WECHAT_PACKAGE,
            VERSION,
            now,
            port,
        )

        assertEquals(WechatSemanticCallExecutionStatus.AWAITING_CALL_STARTED, outcome?.status)
        assertEquals(1, port.actions.count { it == "tap:SEARCH_RESULT" })
        assertEquals(1, port.actions.count { it == "tap:CHAT_INFO_MENU" })
        assertEquals(1, port.actions.count { it == "tap:CHAT_INFO_CONTACT_AVATAR" })
        assertEquals(4, port.actions.count { it == "readProfile" })
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

    @Test
    fun legacyChatAvatarIsNeverUsedAsTheNewChatInfoAvatar() {
        val currentProfile = profile()
        val legacy = currentProfile.copy(points = currentProfile.points
            .minus(WechatCalibrationTarget.CHAT_INFO_MENU)
            .minus(WechatCalibrationTarget.CHAT_INFO_CONTACT_AVATAR)
            .plus(WechatCalibrationTarget.CHAT_CONTACT_AVATAR to WechatNormalizedCalibrationPoint(50_000, 700_000)))
        val broker = WechatCalibratedCallExecutionBroker()
        assertFalse(broker.arm(plan(WechatActionType.START_VOICE_CALL), legacy, VERSION, now))
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
                    xMillionths = 100_000 + target.ordinal * 40_000,
                    yMillionths = 150_000 + target.ordinal * 40_000,
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

    private fun WechatCalibratedCallExecutionBroker.arm(
        plan: WechatActionPlan,
        profile: WechatCalibrationProfile,
        wechatVersion: String,
        now: OffsetDateTime,
    ): Boolean = arm(plan, profile, wechatVersion, capability(plan.action), now)

    private fun capability(
        action: WechatActionType,
        includeActivePage: Boolean = true,
    ): WechatCapabilitySnapshot {
        val activePage = action.activeCallPageType()
        val activeSignature = "c".repeat(64)
        return WechatCapabilitySnapshot(
            remotelyEnabled = true,
            signedRulesTrusted = true,
            combinationApproved = true,
            packageName = WechatSemanticCallContract.WECHAT_PACKAGE,
            wechatVersion = VERSION,
            ruleVersion = "wechat-rule-v1",
            locatorVersion = WechatSemanticCallContract.LOCATOR_VERSION,
            compatibleMinimumRuleVersions = setOf(WechatSemanticCallContract.RULE_VERSION),
            allowedActions = setOf(action),
            allowedPageTypes = mapOf(
                action to if (includeActivePage) setOf(activePage) else emptySet(),
            ),
            allowedPageSignatures = mapOf(
                action to if (includeActivePage) setOf(activeSignature) else emptySet(),
            ),
            appBuildSha256 = "a".repeat(64),
            signingCertificateSha256 = "b".repeat(64),
            deviceManufacturer = "vivo",
            deviceModel = "V2536A",
            androidSdkInt = 36,
            allowedPageSignaturesByType = mapOf(
                action to if (includeActivePage) {
                    mapOf(activePage to setOf(activeSignature))
                } else {
                    emptyMap()
                },
            ),
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
        var rejectedGestureNumber: Int? = null
        var clipboardAvailable = true
        var directSearchAvailable = true
        var audioAvailable = true
        var profileDigest = "b".repeat(64)
        var profileUnavailableReads = 0
        private var searchTextApplied = false
        private var gestureAttempts = 0
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
            val target = targetsByPoint.getValue(point)
            actions += "tap:" + target.name
            gestureAttempts += 1
            val accepted = gestureAttempts != rejectedGestureNumber
            if (accepted && target == WechatCalibrationTarget.GLOBAL_SEARCH_PASTE) {
                searchTextApplied = clipboardAvailable
            }
            return accepted
        }

        override suspend fun longPress(point: WechatCalibrationPixelPoint): Boolean {
            actions += "long:" + targetsByPoint.getValue(point).name
            gestureAttempts += 1
            return gestureAttempts != rejectedGestureNumber
        }

        override fun setSearchText(value: CharArray): Boolean {
            actions += "setText"
            val accepted =
                directSearchAvailable && value.contentEquals("wxid_demo123".toCharArray())
            if (accepted) searchTextApplied = true
            return accepted
        }

        override fun setSensitiveSearchClipboard(value: CharArray): Boolean {
            clipboardWrites += 1
            return clipboardAvailable && value.contentEquals("wxid_demo123".toCharArray())
        }

        override fun clearSearchClipboard() {
            clipboardClears += 1
        }

        override fun readContactProfileEvidence(
            locatorSalt: String,
            now: OffsetDateTime,
        ): WechatSemanticContactProfileEvidence? {
            actions += "readProfile"
            if (profileUnavailableReads > 0) {
                profileUnavailableReads -= 1
                return null
            }
            return WechatSemanticContactProfileEvidence(
                locatorCandidates = listOf(
                    WechatSemanticLocatorEvidence(profileDigest, visibleToUser = true),
                ),
                callEntryCandidates = listOf(
                    WechatSemanticActionNodeEvidence(
                        handle = 10,
                        text = "音视频通话",
                        visibleToUser = true,
                        enabled = true,
                        selfClickable = true,
                    ),
                ),
                capturedAt = now,
            )
        }

        override fun readCallChoiceEvidence(
            now: OffsetDateTime,
        ): WechatSemanticCallChoiceEvidence {
            actions += "readChoice"
            return WechatSemanticCallChoiceEvidence(
                actionCandidates = listOf(
                    WechatSemanticActionNodeEvidence(
                        handle = 21,
                        text = "语音通话",
                        visibleToUser = true,
                        enabled = true,
                        selfClickable = true,
                    ),
                    WechatSemanticActionNodeEvidence(
                        handle = 22,
                        text = "视频通话",
                        visibleToUser = true,
                        enabled = true,
                        selfClickable = true,
                    ),
                ),
                capturedAt = now,
            )
        }

        override fun releasePageEvidence() = Unit

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
