package com.aifriend.feature.wechat

import com.aifriend.contract.model.AllowedAction
import com.aifriend.contract.model.Intent
import com.aifriend.contract.model.MatchedContact
import com.aifriend.contract.model.SpeechProcessingVersions
import com.aifriend.contract.model.TaskSession
import com.aifriend.contract.model.TaskState
import com.aifriend.contract.model.TaskUnderstanding
import com.aifriend.contract.model.WechatActionPlan
import com.aifriend.contract.model.WechatActionType
import com.aifriend.contract.model.WechatTargetLocatorProof
import java.math.BigDecimal
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 微信协调器对语义通话与既有消息路径的分流回归。 */
class WechatExecutionCoordinatorTest {
    private val issuedAt = OffsetDateTime.now().minusSeconds(1)

    @Test
    fun callsUseSemanticBrokerWithoutReadingSignedPageContext() {
        val contextProvider = RecordingContextProvider(disabledContext())
        val semanticBroker = WechatSemanticCallExecutionBroker()
        val calibratedBroker = WechatCalibratedCallExecutionBroker()
        val coordinator = coordinator(
            contextProvider = contextProvider,
            semanticBroker = semanticBroker,
            calibratedBroker = calibratedBroker,
            versionProvider = WechatRuntimeVersionProvider { WECHAT_VERSION },
        )
        val plan = plan(WechatActionType.START_VOICE_CALL)

        val result = coordinator.evaluate(plan, session(Intent.VOICE_CALL), taskCurrent = true)

        assertTrue(result.allowed)
        assertNull(result.denial)
        assertEquals(0, contextProvider.calls)
        assertSame(calibratedBroker.outcomes, coordinator.semanticCallOutcomes)
        assertEquals(WECHAT_VERSION, coordinator.currentWechatVersion())
    }

    @Test
    fun unavailableOrChangedWechatVersionFailsClosedBeforeSignedContext() {
        val unavailableContext = RecordingContextProvider(disabledContext())
        val unavailable = coordinator(
            contextProvider = unavailableContext,
            versionProvider = WechatRuntimeVersionProvider { null },
        ).evaluate(
            plan(WechatActionType.START_VIDEO_CALL),
            session(Intent.VIDEO_CALL),
            taskCurrent = true,
        )
        val changedContext = RecordingContextProvider(disabledContext())
        val changed = coordinator(
            contextProvider = changedContext,
            versionProvider = WechatRuntimeVersionProvider { "8.0.changed" },
        ).evaluate(
            plan(WechatActionType.START_VIDEO_CALL, planId = SECOND_PLAN_ID),
            session(Intent.VIDEO_CALL),
            taskCurrent = true,
        )

        assertFalse(unavailable.allowed)
        assertEquals(WechatExecutionDenial.WECHAT_VERSION_MISMATCH, unavailable.denial)
        assertFalse(changed.allowed)
        assertEquals(WechatExecutionDenial.CALIBRATION_PROFILE_MISSING, changed.denial)
        assertEquals(0, unavailableContext.calls)
        assertEquals(0, changedContext.calls)
    }

    @Test
    fun callsFailClosedBeforeBrokerWhenExactCalibrationProfileIsMissing() {
        val contextProvider = RecordingContextProvider(disabledContext())
        val coordinator = coordinator(
            contextProvider = contextProvider,
            versionProvider = WechatRuntimeVersionProvider { WECHAT_VERSION },
            calibrationRegistry = EmptyCalibrationRegistry,
        )

        val result = coordinator.evaluate(
            plan(WechatActionType.START_VOICE_CALL),
            session(Intent.VOICE_CALL),
            taskCurrent = true,
        )

        assertFalse(result.allowed)
        assertEquals(WechatExecutionDenial.CALIBRATION_PROFILE_MISSING, result.denial)
        assertEquals(0, contextProvider.calls)
    }

    @Test
    fun messagesKeepSignedRulePathAndDoNotReadRuntimeVersion() {
        val plan = plan(WechatActionType.SEND_AUDIO_AND_TEXT)
        val contextProvider = RecordingContextProvider(signedMessageContext(plan))
        var versionReads = 0
        val coordinator = coordinator(
            contextProvider = contextProvider,
            versionProvider = WechatRuntimeVersionProvider {
                versionReads += 1
                null
            },
        )

        val result = coordinator.evaluate(plan, session(Intent.SEND_MESSAGE), taskCurrent = true)

        assertTrue(result.allowed)
        assertEquals(1, contextProvider.calls)
        assertEquals(0, versionReads)
    }

    @Test
    fun clearAlsoRemovesPendingSemanticCall() {
        val semanticBroker = WechatSemanticCallExecutionBroker()
        val calibratedBroker = WechatCalibratedCallExecutionBroker()
        val coordinator = coordinator(
            contextProvider = RecordingContextProvider(disabledContext()),
            semanticBroker = semanticBroker,
            calibratedBroker = calibratedBroker,
            versionProvider = WechatRuntimeVersionProvider { WECHAT_VERSION },
        )
        val plan = plan(WechatActionType.START_VOICE_CALL)
        assertTrue(coordinator.evaluate(plan, session(Intent.VOICE_CALL), true).allowed)

        coordinator.clear()

        assertNull(semanticBroker.interrupt(plan.planId))
        assertNull(calibratedBroker.interrupt(plan.planId))
    }

    private fun coordinator(
        contextProvider: RecordingContextProvider,
        semanticBroker: WechatSemanticCallExecutionBroker = WechatSemanticCallExecutionBroker(),
        calibratedBroker: WechatCalibratedCallExecutionBroker =
            WechatCalibratedCallExecutionBroker(),
        versionProvider: WechatRuntimeVersionProvider,
        calibrationRegistry: WechatCalibrationProfileRegistry = FixedCalibrationRegistry,
    ): WechatExecutionCoordinator = WechatExecutionCoordinator(
        admission = WechatExecutionAdmission(),
        contextProvider = contextProvider,
        locatorProofVerifier = FixedProofVerifier(),
        semanticCallExecutionBroker = semanticBroker,
        calibratedCallExecutionBroker = calibratedBroker,
        runtimeVersionProvider = versionProvider,
        calibrationFingerprintProvider = WechatCalibrationFingerprintProvider {
            calibrationKey(it)
        },
        calibrationProfileRegistry = calibrationRegistry,
        observationBroker = WechatPageObservationBroker(),
        actionBroker = WechatVerifiedContactProfileActionBroker(),
        directChatTransitionBroker = WechatDirectChatTransitionBroker(),
        callChoiceActionBroker = WechatCallChoiceActionBroker(),
        callStartedTransitionBroker = WechatCallStartedTransitionBroker(),
    )

    private fun calibrationKey(wechatVersion: String) = WechatCalibrationProfileKey(
        manufacturer = "test-manufacturer",
        model = "test-model",
        androidSdkInt = 35,
        displayWidthPixels = 1080,
        displayHeightPixels = 2400,
        densityDpi = 420,
        fontScalePermille = 1_000,
        orientation = WechatCalibrationOrientation.PORTRAIT,
        wechatVersion = wechatVersion,
    )

    private fun calibrationProfile() = WechatCalibrationProfile(
        key = calibrationKey(WECHAT_VERSION),
        points = WechatCalibrationTarget.entries.associateWith { target ->
            WechatNormalizedCalibrationPoint(
                xMillionths = 100_000 + target.ordinal,
                yMillionths = 200_000 + target.ordinal,
            )
        },
        updatedAtEpochMillis = 1L,
    )

    private object EmptyCalibrationRegistry : WechatCalibrationProfileRegistry {
        override fun list(): List<WechatCalibrationProfile> = emptyList()
        override fun findExact(key: WechatCalibrationProfileKey): WechatCalibrationProfile? = null
        override fun upsert(profile: WechatCalibrationProfile): Boolean = false
        override fun remove(key: WechatCalibrationProfileKey): Boolean = false
    }

    private val FixedCalibrationRegistry: WechatCalibrationProfileRegistry
        get() = object : WechatCalibrationProfileRegistry {
            private val profile = calibrationProfile()
            override fun list(): List<WechatCalibrationProfile> = listOf(profile)
            override fun findExact(key: WechatCalibrationProfileKey): WechatCalibrationProfile? =
                profile.takeIf { it.key == key }
            override fun upsert(profile: WechatCalibrationProfile): Boolean = false
            override fun remove(key: WechatCalibrationProfileKey): Boolean = false
        }

    private fun plan(
        action: WechatActionType,
        planId: String = PLAN_ID,
    ): WechatActionPlan {
        val expiresAt = issuedAt.plusSeconds(30)
        return WechatActionPlan(
            planId = planId,
            action = action,
            contactId = CONTACT_ID,
            summaryHash = SUMMARY_HASH,
            minimumRuleVersion = if (action == WechatActionType.SEND_AUDIO_AND_TEXT) {
                SIGNED_RULE_VERSION
            } else {
                WechatSemanticCallContract.RULE_VERSION
            },
            expiresAt = expiresAt,
            targetSearchLocator = "wxid_demo123",
            targetLocatorProof = WechatTargetLocatorProof(
                proofVersion = WechatTargetLocatorProof.ProofVersion.WECHAT_LOCATOR_PROOF_V1,
                keyId = "test-key",
                contactVersion = 7,
                wechatVersion = WECHAT_VERSION,
                locatorVersion = WechatSemanticCallContract.LOCATOR_VERSION,
                salt = "0".repeat(32),
                targetLocatorSha256 = LOCATOR_DIGEST,
                issuedAt = issuedAt,
                expiresAt = expiresAt,
                signature = "A".repeat(86),
            ),
            audioObjectId = if (action == WechatActionType.SEND_AUDIO_AND_TEXT) AUDIO_ID else null,
        )
    }

    private fun session(intent: Intent): TaskSession = TaskSession(
        sessionId = "33333333-3333-3333-3333-333333333333",
        sessionVersion = 4,
        state = TaskState.EXECUTING,
        candidates = emptyList(),
        allowedActions = emptySet<AllowedAction>(),
        expiresAt = issuedAt.plusMinutes(1),
        understanding = TaskUnderstanding(
            intent = intent,
            transcript = "<sensitive-test-content>",
            effectiveAudioRanges = emptyList(),
            corrections = emptyList(),
            confidence = BigDecimal.ONE,
            processingVersions = SpeechProcessingVersions(
                dialectCode = "wugang",
                dialectPackageVersion = "dialect-v1",
                primaryAsrModelVersion = "asr-v1",
                mandarinAssistVersion = "mandarin-v1",
                fusionRuleVersion = "fusion-v1",
                alignmentVersion = "alignment-v1",
                templateModelVersion = "template-v1",
                thresholdVersion = "threshold-v1",
            ),
            contact = MatchedContact(CONTACT_ID, "测试亲友", "测试称呼"),
        ),
        spokenSummary = "测试摘要",
        summaryHash = SUMMARY_HASH,
        channelResult = null,
    )

    private fun disabledContext(): WechatExecutionContext = WechatExecutionContext(
        capability = WechatCapabilitySnapshot.disabled(),
        pageSnapshot = null,
        targetLocatorProof = null,
    )

    private fun signedMessageContext(plan: WechatActionPlan): WechatExecutionContext {
        val action = WechatActionType.SEND_AUDIO_AND_TEXT
        val capability = WechatCapabilitySnapshot(
            remotelyEnabled = true,
            signedRulesTrusted = true,
            combinationApproved = true,
            packageName = WechatSemanticCallContract.WECHAT_PACKAGE,
            wechatVersion = WECHAT_VERSION,
            ruleVersion = SIGNED_RULE_VERSION,
            locatorVersion = WechatSemanticCallContract.LOCATOR_VERSION,
            compatibleMinimumRuleVersions = setOf(SIGNED_RULE_VERSION),
            allowedActions = setOf(action),
            allowedPageTypes = mapOf(
                action to setOf(WechatPageType.CONTACT_PROFILE, WechatPageType.DIRECT_CHAT),
            ),
            allowedPageSignatures = mapOf(action to setOf(PAGE_SIGNATURE)),
            appBuildSha256 = "c".repeat(64),
            signingCertificateSha256 = "d".repeat(64),
            deviceManufacturer = "test-manufacturer",
            deviceModel = "test-model",
            androidSdkInt = 35,
            allowedPageSignaturesByType = mapOf(
                action to mapOf(
                    WechatPageType.CONTACT_PROFILE to setOf(PAGE_SIGNATURE),
                    WechatPageType.DIRECT_CHAT to setOf("b".repeat(64)),
                ),
            ),
        )
        return WechatExecutionContext(
            capability = capability,
            pageSnapshot = WechatPageSnapshot(
                packageName = WechatSemanticCallContract.WECHAT_PACKAGE,
                wechatVersion = WECHAT_VERSION,
                ruleVersion = SIGNED_RULE_VERSION,
                locatorVersion = WechatSemanticCallContract.LOCATOR_VERSION,
                pageType = WechatPageType.CONTACT_PROFILE,
                pageSignatureSha256 = PAGE_SIGNATURE,
                targetLocatorSha256 = LOCATOR_DIGEST,
                targetMatchCount = 1,
                actionNodeMatchCount = 1,
                capturedAt = OffsetDateTime.now().minusSeconds(1),
            ),
            targetLocatorProof = verifiedProof(plan),
        )
    }

    private fun verifiedProof(plan: WechatActionPlan): VerifiedWechatTargetLocatorProof =
        VerifiedWechatTargetLocatorProof(
            planId = plan.planId,
            contactId = plan.contactId,
            contactVersion = plan.targetLocatorProof.contactVersion,
            wechatVersion = plan.targetLocatorProof.wechatVersion,
            locatorVersion = plan.targetLocatorProof.locatorVersion,
            targetLocatorSha256 = plan.targetLocatorProof.targetLocatorSha256,
            issuedAt = plan.targetLocatorProof.issuedAt,
            expiresAt = plan.targetLocatorProof.expiresAt,
            keyId = plan.targetLocatorProof.keyId,
        )

    private inner class FixedProofVerifier : WechatTargetLocatorProofVerifier {
        override fun verify(
            plan: WechatActionPlan,
            now: OffsetDateTime,
        ): VerifiedWechatTargetLocatorProof = verifiedProof(plan)
    }

    private class RecordingContextProvider(
        private val context: WechatExecutionContext,
    ) : WechatExecutionContextProvider {
        var calls: Int = 0
            private set

        override fun current(
            plan: WechatActionPlan,
            now: OffsetDateTime,
        ): WechatExecutionContext {
            calls += 1
            return context
        }
    }

    private companion object {
        const val PLAN_ID = "44444444-4444-4444-4444-444444444444"
        const val SECOND_PLAN_ID = "55555555-5555-5555-5555-555555555555"
        const val CONTACT_ID = "11111111-1111-1111-1111-111111111111"
        const val AUDIO_ID = "66666666-6666-6666-6666-666666666666"
        const val SUMMARY_HASH =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val LOCATOR_DIGEST =
            "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val PAGE_SIGNATURE =
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        const val WECHAT_VERSION = "8.0.test"
        const val SIGNED_RULE_VERSION = "wechat-rule-v1"
    }
}
