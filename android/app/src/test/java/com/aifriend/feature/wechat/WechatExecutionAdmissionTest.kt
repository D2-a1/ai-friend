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
import com.aifriend.feature.task.TaskConfirmationOutcome
import java.math.BigDecimal
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 微信有限动作准入的精确绑定、版本、定位和唯一性测试。 */
class WechatExecutionAdmissionTest {
    private val admission = WechatExecutionAdmission()
    private val now = OffsetDateTime.parse("2026-08-19T08:00:00Z")

    @Test
    fun exactMessageContextOnlyGrantsAdmissionWithoutExecuting() {
        val result = evaluate()

        assertTrue(result.allowed)
        assertEquals(null, result.denial)
    }

    @Test
    fun staleTaskGenerationFailsBeforeAnyWechatFact() {
        val result = evaluate(taskCurrent = false)

        assertDenied(WechatExecutionDenial.TASK_NOT_CURRENT, result)
    }

    @Test
    fun expiredPlanAndNonExecutingSessionFailClosed() {
        assertDenied(
            WechatExecutionDenial.PLAN_EXPIRED,
            evaluate(plan = plan(expiresAt = now)),
        )
        assertDenied(
            WechatExecutionDenial.SESSION_NOT_EXECUTING,
            evaluate(session = session(state = TaskState.CANCELLED)),
        )
        assertDenied(
            WechatExecutionDenial.SESSION_EXPIRED,
            evaluate(session = session(expiresAt = now)),
        )
    }

    @Test
    fun summaryAndContactMustMatchCurrentSessionExactly() {
        assertDenied(
            WechatExecutionDenial.SUMMARY_MISMATCH,
            evaluate(plan = plan(summaryHash = "f".repeat(64))),
        )
        assertDenied(
            WechatExecutionDenial.CONTACT_MISMATCH,
            evaluate(plan = plan(contactId = "22222222-2222-2222-2222-222222222222")),
        )
    }

    @Test
    fun voiceAndVideoActionsCannotReplaceEachOther() {
        val voiceSession = session(intent = Intent.VOICE_CALL)
        val videoPlan = plan(action = WechatActionType.START_VIDEO_CALL)

        assertDenied(
            WechatExecutionDenial.INTENT_ACTION_MISMATCH,
            evaluate(plan = videoPlan, session = voiceSession, context = context(videoPlan.action)),
        )
    }

    @Test
    fun messageRequiresAudioAndCallsRejectMessageAudio() {
        assertDenied(
            WechatExecutionDenial.ACTION_PAYLOAD_INVALID,
            evaluate(plan = plan(audioObjectId = null)),
        )
        val voicePlan = plan(
            action = WechatActionType.START_VOICE_CALL,
            audioObjectId = AUDIO_ID,
        )
        assertDenied(
            WechatExecutionDenial.ACTION_PAYLOAD_INVALID,
            evaluate(
                plan = voicePlan,
                session = session(intent = Intent.VOICE_CALL),
                context = context(voicePlan.action),
            ),
        )
    }

    @Test
    fun remoteFuseAndUnsignedRulesAlwaysDeny() {
        val disabled = context().copy(
            capability = capability().copy(remotelyEnabled = false),
        )
        assertDenied(WechatExecutionDenial.REMOTELY_DISABLED, evaluate(context = disabled))

        val unsigned = context().copy(
            capability = capability().copy(signedRulesTrusted = false),
        )
        assertDenied(WechatExecutionDenial.RULE_SET_UNTRUSTED, evaluate(context = unsigned))
    }

    @Test
    fun unknownMinimumRuleVersionDoesNotUseLexicalGuessing() {
        val incompatible = context().copy(
            capability = capability().copy(compatibleMinimumRuleVersions = setOf("wechat-rule-v1")),
        )

        assertDenied(
            WechatExecutionDenial.RULE_VERSION_UNSUPPORTED,
            evaluate(plan = plan(minimumRuleVersion = "wechat-rule-v2"), context = incompatible),
        )
    }

    @Test
    fun missingLocatorProofExposesCurrentContractGapAndDenies() {
        val noProof = context().copy(targetLocatorProof = null)

        assertDenied(
            WechatExecutionDenial.TARGET_LOCATOR_PROOF_MISSING,
            evaluate(context = noProof),
        )
    }

    @Test
    fun proofMustBindPlanContactVersionDigestAndExpiry() {
        val wrongPlan = context().copy(
            targetLocatorProof = proof().copy(planId = "different-plan"),
        )
        assertDenied(
            WechatExecutionDenial.TARGET_LOCATOR_PROOF_INVALID,
            evaluate(context = wrongPlan),
        )

        val wrongDigest = context().copy(
            targetLocatorProof = proof().copy(targetLocatorSha256 = "not-a-digest"),
        )
        assertDenied(
            WechatExecutionDenial.TARGET_LOCATOR_PROOF_INVALID,
            evaluate(context = wrongDigest),
        )
    }

    @Test
    fun staleOrUnsupportedPageNeverFallsBackToCoordinates() {
        val stale = context().copy(
            pageSnapshot = page().copy(capturedAt = now.minusSeconds(6)),
        )
        assertDenied(WechatExecutionDenial.PAGE_SNAPSHOT_STALE, evaluate(context = stale))

        val unsupported = context().copy(
            pageSnapshot = page().copy(pageType = WechatPageType.HOME),
        )
        assertDenied(WechatExecutionDenial.PAGE_UNSUPPORTED, evaluate(context = unsupported))
    }

    @Test
    fun targetAndActionNodeMustEachBeUnique() {
        val duplicateTarget = context().copy(
            pageSnapshot = page().copy(targetMatchCount = 2),
        )
        assertDenied(WechatExecutionDenial.TARGET_NOT_UNIQUE, evaluate(context = duplicateTarget))

        val duplicateAction = context().copy(
            pageSnapshot = page().copy(actionNodeMatchCount = 2),
        )
        assertDenied(
            WechatExecutionDenial.ACTION_NODE_NOT_UNIQUE,
            evaluate(context = duplicateAction),
        )
    }

    @Test
    fun defaultProductionContextIsDisabledAndContainsNoPageOrLocator() {
        val defaultContext = SignedWechatExecutionContextProvider(
            WechatRulePackageRegistry { null },
            WechatPageObservationBroker(),
        ).current(plan(), now)

        assertFalse(defaultContext.capability.remotelyEnabled)
        assertEquals(null, defaultContext.pageSnapshot)
        assertEquals(null, defaultContext.targetLocatorProof)
        assertDenied(
            WechatExecutionDenial.REMOTELY_DISABLED,
            evaluate(context = defaultContext),
        )
    }

    @Test
    fun actionPlanIsRetainedWhileDiagnosticTextRedactsSensitiveFields() {
        val plan = plan()
        val outcome = TaskConfirmationOutcome(session(), plan)
        val page = page()
        val locatorProof = proof()

        assertEquals(plan, outcome.actionPlan)
        assertFalse(outcome.toString().contains(CONTACT_ID))
        assertFalse(outcome.toString().contains(SUMMARY_HASH))
        assertFalse(page.toString().contains(PAGE_SIGNATURE))
        assertFalse(page.toString().contains(LOCATOR_DIGEST))
        assertFalse(locatorProof.toString().contains(PLAN_ID))
        assertFalse(locatorProof.toString().contains(CONTACT_ID))
        assertFalse(locatorProof.toString().contains(LOCATOR_DIGEST))
    }

    private fun evaluate(
        plan: WechatActionPlan = plan(),
        session: TaskSession = session(),
        taskCurrent: Boolean = true,
        context: WechatExecutionContext = context(plan.action),
    ): WechatExecutionAdmissionResult = admission.evaluate(
        plan = plan,
        session = session,
        taskCurrent = taskCurrent,
        context = context,
        now = now,
    )

    private fun plan(
        action: WechatActionType = WechatActionType.SEND_AUDIO_AND_TEXT,
        contactId: String = CONTACT_ID,
        summaryHash: String = SUMMARY_HASH,
        minimumRuleVersion: String = MINIMUM_RULE_VERSION,
        expiresAt: OffsetDateTime = now.plusSeconds(30),
        audioObjectId: String? = if (action == WechatActionType.SEND_AUDIO_AND_TEXT) AUDIO_ID else null,
    ): WechatActionPlan = WechatActionPlan(
        planId = PLAN_ID,
        action = action,
        contactId = contactId,
        summaryHash = summaryHash,
        minimumRuleVersion = minimumRuleVersion,
        expiresAt = expiresAt,
        targetSearchLocator = "wxid_demo123",
        targetLocatorProof = rawProof(expiresAt),
        audioObjectId = audioObjectId,
    )

    private fun rawProof(expiresAt: OffsetDateTime): WechatTargetLocatorProof =
        WechatTargetLocatorProof(
            proofVersion = WechatTargetLocatorProof.ProofVersion.WECHAT_LOCATOR_PROOF_V1,
            keyId = "test-key",
            contactVersion = 7,
            wechatVersion = WECHAT_VERSION,
            locatorVersion = LOCATOR_VERSION,
            salt = "0".repeat(32),
            targetLocatorSha256 = LOCATOR_DIGEST,
            issuedAt = now,
            expiresAt = expiresAt,
            signature = "A".repeat(86),
        )

    private fun session(
        state: TaskState = TaskState.EXECUTING,
        intent: Intent = Intent.SEND_MESSAGE,
        expiresAt: OffsetDateTime = now.plusMinutes(1),
    ): TaskSession = TaskSession(
        sessionId = "33333333-3333-3333-3333-333333333333",
        sessionVersion = 4,
        state = state,
        candidates = emptyList(),
        allowedActions = emptySet<AllowedAction>(),
        expiresAt = expiresAt,
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

    private fun context(
        action: WechatActionType = WechatActionType.SEND_AUDIO_AND_TEXT,
    ): WechatExecutionContext = WechatExecutionContext(
        capability = capability(action),
        pageSnapshot = page(),
        targetLocatorProof = proof(),
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
        compatibleMinimumRuleVersions = setOf(MINIMUM_RULE_VERSION),
        allowedActions = setOf(action),
        allowedPageTypes = mapOf(action to setOf(WechatPageType.DIRECT_CHAT)),
        allowedPageSignatures = mapOf(action to setOf(PAGE_SIGNATURE)),
        appBuildSha256 = "c".repeat(64),
        signingCertificateSha256 = "d".repeat(64),
        deviceManufacturer = "test-manufacturer",
        deviceModel = "test-model",
        androidSdkInt = 35,
    )

    private fun page(): WechatPageSnapshot = WechatPageSnapshot(
        packageName = WECHAT_PACKAGE,
        wechatVersion = WECHAT_VERSION,
        ruleVersion = RULE_VERSION,
        locatorVersion = LOCATOR_VERSION,
        pageType = WechatPageType.DIRECT_CHAT,
        pageSignatureSha256 = PAGE_SIGNATURE,
        targetLocatorSha256 = LOCATOR_DIGEST,
        targetMatchCount = 1,
        actionNodeMatchCount = 1,
        capturedAt = now.minusSeconds(1),
    )

    private fun proof(): VerifiedWechatTargetLocatorProof = VerifiedWechatTargetLocatorProof(
        planId = PLAN_ID,
        contactId = CONTACT_ID,
        contactVersion = 7,
        wechatVersion = WECHAT_VERSION,
        locatorVersion = LOCATOR_VERSION,
        targetLocatorSha256 = LOCATOR_DIGEST,
        issuedAt = now,
        expiresAt = now.plusSeconds(30),
        keyId = "test-key",
    )

    private fun assertDenied(
        reason: WechatExecutionDenial,
        result: WechatExecutionAdmissionResult,
    ) {
        assertFalse(result.allowed)
        assertEquals(reason, result.denial)
    }

    private companion object {
        const val PLAN_ID = "44444444-4444-4444-4444-444444444444"
        const val CONTACT_ID = "11111111-1111-1111-1111-111111111111"
        const val AUDIO_ID = "55555555-5555-5555-5555-555555555555"
        const val SUMMARY_HASH =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val PAGE_SIGNATURE =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val LOCATOR_DIGEST =
            "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val WECHAT_VERSION = "8.0.test"
        const val RULE_VERSION = "wechat-rule-v2"
        const val MINIMUM_RULE_VERSION = "wechat-rule-v1"
        const val LOCATOR_VERSION = "locator-v1"
    }
}
