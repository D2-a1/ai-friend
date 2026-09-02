package com.aifriend.feature.wechat

import com.aifriend.contract.model.AllowedAction
import com.aifriend.contract.model.ChannelPartResult
import com.aifriend.contract.model.ChannelResult
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 零采样语义通话的准入、严格唯一节点、一次性消费和有限结果测试。 */
class WechatSemanticCallExecutionTest {
    private val now = OffsetDateTime.parse("2026-08-31T06:00:00Z")

    @Test
    fun matchingProofAllowsAnyCurrentWechatVersionWithoutVersionAllowlist() {
        listOf("8.0.76", "8.0.99", "9.1.234").forEachIndexed { index, version ->
            val broker = WechatSemanticCallExecutionBroker()

            val result = broker.arm(
                input(
                    planId = "plan_version_$index",
                    wechatVersion = version,
                ),
            )

            assertTrue(result.armed)
            assertNull(result.denial)
        }
    }

    @Test
    fun missingVerifiedProofAndVersionMismatchFailAdmission() {
        val missing = WechatSemanticCallExecutionBroker().arm(
            input(verifiedProof = null),
        )
        val mismatch = WechatSemanticCallExecutionBroker().arm(
            input(currentWechatVersion = "8.0.77"),
        )

        assertEquals(WechatSemanticCallDenial.VERIFIED_PROOF_MISSING, missing.denial)
        assertEquals(WechatSemanticCallDenial.WECHAT_VERSION_MISMATCH, mismatch.denial)
    }

    @Test
    fun semanticAndLocatorVersionsAreExactButWechatVersionIsNotFixed() {
        val wrongSemantic = WechatSemanticCallExecutionBroker().arm(
            input(semanticRuleVersion = "wechat-rule-v1"),
        )
        val wrongLocatorPlan = plan(locatorVersion = "wechat-contact-profile-v2")
        val wrongLocator = WechatSemanticCallExecutionBroker().arm(
            input(
                plan = wrongLocatorPlan,
                verifiedProof = verifiedProof(wrongLocatorPlan),
            ),
        )

        assertEquals(WechatSemanticCallDenial.SEMANTIC_RULE_MISMATCH, wrongSemantic.denial)
        assertEquals(WechatSemanticCallDenial.LOCATOR_VERSION_MISMATCH, wrongLocator.denial)
    }

    @Test
    fun taskSessionPlanIntentAndProofTtlRemainRequired() {
        val stalePlan = plan(expiresAt = now)
        val stale = WechatSemanticCallExecutionBroker().arm(
            input(plan = stalePlan, verifiedProof = verifiedProof(stalePlan)),
        )
        val wrongIntentPlan = plan(action = WechatActionType.START_VOICE_CALL)
        val wrongIntent = WechatSemanticCallExecutionBroker().arm(
            input(
                plan = wrongIntentPlan,
                session = session(Intent.VIDEO_CALL),
                verifiedProof = verifiedProof(wrongIntentPlan),
            ),
        )
        val oldTask = WechatSemanticCallExecutionBroker().arm(input(taskCurrent = false))

        assertEquals(WechatSemanticCallDenial.PLAN_EXPIRED, stale.denial)
        assertEquals(WechatSemanticCallDenial.INTENT_ACTION_MISMATCH, wrongIntent.denial)
        assertEquals(WechatSemanticCallDenial.TASK_NOT_CURRENT, oldTask.denial)
    }

    @Test
    fun executingSummaryContactPayloadAndVerifiedProofBindingsRemainRequired() {
        val nonExecuting = WechatSemanticCallExecutionBroker().arm(
            input(session = session(Intent.VOICE_CALL, state = TaskState.CREATED)),
        )
        val wrongSummary = WechatSemanticCallExecutionBroker().arm(
            input(session = session(Intent.VOICE_CALL, summaryHash = "c".repeat(64))),
        )
        val wrongContact = WechatSemanticCallExecutionBroker().arm(
            input(session = session(Intent.VOICE_CALL, contactId = "other-contact")),
        )
        val payloadPlan = plan(audioObjectId = "unexpected-audio")
        val unexpectedPayload = WechatSemanticCallExecutionBroker().arm(
            input(plan = payloadPlan, verifiedProof = verifiedProof(payloadPlan)),
        )
        val proofPlan = plan()
        val forgedVerifiedFact = WechatSemanticCallExecutionBroker().arm(
            input(
                plan = proofPlan,
                verifiedProof = verifiedProof(proofPlan).copy(
                    targetLocatorSha256 = "c".repeat(64),
                ),
            ),
        )

        assertEquals(WechatSemanticCallDenial.SESSION_NOT_EXECUTING, nonExecuting.denial)
        assertEquals(WechatSemanticCallDenial.SUMMARY_MISMATCH, wrongSummary.denial)
        assertEquals(WechatSemanticCallDenial.CONTACT_MISMATCH, wrongContact.denial)
        assertEquals(WechatSemanticCallDenial.ACTION_PAYLOAD_INVALID, unexpectedPayload.denial)
        assertEquals(WechatSemanticCallDenial.VERIFIED_PROOF_INVALID, forgedVerifiedFact.denial)
    }

    @Test
    fun exactVoiceFlowClicksEachStageOnceAndOnlyReportsHandedToWechat() {
        val harness = harness(WechatActionType.START_VOICE_CALL)
        harness.port.choiceEvidence = choiceEvidence(
            listOf(
                node(handle = 8, text = "视频通话"),
                node(handle = 7, text = "语音通话"),
            ),
            now.plusNanos(2),
        )

        val profile = harness.executor.executeContactProfile(
            harness.plan.planId,
            WECHAT_PACKAGE,
            WECHAT_VERSION,
            now.plusNanos(1),
            harness.port,
        )
        val choice = harness.executor.executeCallChoice(
            harness.plan.planId,
            WECHAT_PACKAGE,
            WECHAT_VERSION,
            now.plusNanos(2),
            harness.port,
        )

        assertEquals(WechatSemanticCallExecutionStatus.AWAITING_CALL_CHOICE, profile?.status)
        assertNull(profile?.finalDeliveryReportOrNull())
        assertEquals(WechatSemanticCallExecutionStatus.HANDED_TO_WECHAT, choice?.status)
        assertEquals(1, harness.port.profileClickCount)
        assertEquals(1, harness.port.choiceClickCount)
        assertEquals(listOf("0".repeat(32)), harness.port.locatorSalts)
        assertEquals(listOf(7), harness.port.clickedChoiceHandles)
        val report = choice?.finalDeliveryReportOrNull()
        assertEquals(ChannelResult.OPENED, report?.result)
        assertEquals(ChannelPartResult.Part.CALL, report?.parts?.single()?.part)
        assertEquals(
            ChannelPartResult.Result.HANDED_TO_WECHAT,
            report?.parts?.single()?.result,
        )
        assertFalse(report?.parts?.single()?.result == ChannelPartResult.Result.CALL_STARTED)
    }

    @Test
    fun exactVideoFlowSelectsVideoWithoutCrossingToVoice() {
        val harness = harness(WechatActionType.START_VIDEO_CALL)
        harness.port.choiceEvidence = choiceEvidence(
            listOf(
                node(handle = 8, text = "语音通话"),
                node(handle = 9, text = "视频通话"),
            ),
            now.plusNanos(2),
        )

        openProfile(harness)
        val result = harness.executor.executeCallChoice(
            harness.plan.planId,
            WECHAT_PACKAGE,
            WECHAT_VERSION,
            now.plusNanos(2),
            harness.port,
        )

        assertEquals(WechatSemanticCallExecutionStatus.HANDED_TO_WECHAT, result?.status)
        assertEquals(listOf(9), harness.port.clickedChoiceHandles)
    }

    @Test
    fun loadingEventsDoNotConsumeEitherStageBeforeCompletePageIsReady() {
        val harness = harness(planId = "loading_sequence")
        harness.port.profileEvidence = null

        assertFalse(
            harness.executor.isCurrentStepReady(now.plusNanos(1), harness.port),
        )
        harness.port.profileEvidence = profileEvidence()
        assertTrue(
            harness.executor.isCurrentStepReady(now.plusNanos(1), harness.port),
        )
        assertEquals(
            WechatSemanticCallExecutionStatus.AWAITING_CALL_CHOICE,
            openProfile(harness)?.status,
        )

        harness.port.choiceEvidence = choiceEvidence(
            listOf(node(text = "语音通话")),
            now.plusNanos(2),
        )
        assertFalse(
            harness.executor.isCurrentStepReady(now.plusNanos(2), harness.port),
        )
        harness.port.choiceEvidence = choiceEvidence(
            listOf(
                node(text = "语音通话"),
                node(handle = 2, text = "视频通话"),
            ),
            now.plusNanos(2),
        )
        assertTrue(
            harness.executor.isCurrentStepReady(now.plusNanos(2), harness.port),
        )
        val completed = harness.executor.executeCallChoice(
            harness.plan.planId,
            WECHAT_PACKAGE,
            WECHAT_VERSION,
            now.plusNanos(2),
            harness.port,
        )

        assertEquals(WechatSemanticCallExecutionStatus.HANDED_TO_WECHAT, completed?.status)
        assertEquals(1, harness.port.profileClickCount)
        assertEquals(1, harness.port.choiceClickCount)
    }

    @Test
    fun profileRequiresOneVisibleMatchingLocator() {
        val cases = listOf(
            emptyList<WechatSemanticLocatorEvidence>() to
                WechatSemanticCallExecutionStatus.PROFILE_TARGET_NOT_UNIQUE,
            listOf(locator(), locator()) to
                WechatSemanticCallExecutionStatus.PROFILE_TARGET_NOT_UNIQUE,
            listOf(locator(visible = false)) to
                WechatSemanticCallExecutionStatus.PROFILE_TARGET_NOT_VISIBLE,
            listOf(locator(digest = "9".repeat(64))) to
                WechatSemanticCallExecutionStatus.PROFILE_TARGET_MISMATCH,
        )

        cases.forEachIndexed { index, (locators, expected) ->
            val harness = harness(planId = "profile_locator_$index")
            harness.port.profileEvidence = profileEvidence(locators = locators)

            val result = openProfile(harness)

            assertEquals(expected, result?.status)
            assertEquals(0, harness.port.profileClickCount)
        }
    }

    @Test
    fun staleProfileEvidenceFailsBeforeAnyClick() {
        val harness = harness()
        harness.port.profileEvidence = profileEvidence().copy(capturedAt = now.minusNanos(1))

        val result = openProfile(harness)

        assertEquals(
            WechatSemanticCallExecutionStatus.PROFILE_EVIDENCE_UNAVAILABLE,
            result?.status,
        )
        assertEquals(0, harness.port.profileClickCount)
    }

    @Test
    fun profileRequiresOneVisibleEnabledSelfClickableExactCallEntry() {
        val cases = listOf(
            emptyList<WechatSemanticActionNodeEvidence>() to
                WechatSemanticCallExecutionStatus.PROFILE_ACTION_NOT_UNIQUE,
            listOf(node(), node(handle = 2)) to
                WechatSemanticCallExecutionStatus.PROFILE_ACTION_NOT_UNIQUE,
            listOf(node(text = " 音视频通话 ")) to
                WechatSemanticCallExecutionStatus.PROFILE_ACTION_NOT_UNIQUE,
            listOf(node(visible = false)) to
                WechatSemanticCallExecutionStatus.PROFILE_ACTION_NOT_CLICKABLE,
            listOf(node(enabled = false)) to
                WechatSemanticCallExecutionStatus.PROFILE_ACTION_NOT_CLICKABLE,
            listOf(node(clickable = false)) to
                WechatSemanticCallExecutionStatus.PROFILE_ACTION_NOT_CLICKABLE,
        )

        cases.forEachIndexed { index, (actions, expected) ->
            val harness = harness(planId = "profile_action_$index")
            harness.port.profileEvidence = profileEvidence(actions = actions)

            val result = openProfile(harness)

            assertEquals(expected, result?.status)
            assertEquals(0, harness.port.profileClickCount)
        }
    }

    @Test
    fun choiceRequiresExpectedTypeOnlyAndOneClickableNode() {
        val cases = listOf(
            listOf(node(text = "视频通话")) to
                WechatSemanticCallExecutionStatus.CHOICE_ACTION_NOT_UNIQUE,
            listOf(
                node(text = "语音通话"),
                node(handle = 2, text = "语音通话"),
                node(handle = 3, text = "视频通话"),
            ) to
                WechatSemanticCallExecutionStatus.CHOICE_ACTION_NOT_UNIQUE,
            listOf(node(text = " 语音通话 "), node(handle = 2, text = "视频通话")) to
                WechatSemanticCallExecutionStatus.CHOICE_ACTION_NOT_UNIQUE,
            listOf(node(text = "语音通话")) to
                WechatSemanticCallExecutionStatus.CHOICE_TYPE_CONFLICT,
            listOf(
                node(text = "语音通话"),
                node(handle = 2, text = "视频通话"),
                node(handle = 3, text = "视频通话"),
            ) to
                WechatSemanticCallExecutionStatus.CHOICE_TYPE_CONFLICT,
            listOf(
                node(text = "语音通话", visible = false),
                node(handle = 2, text = "视频通话"),
            ) to
                WechatSemanticCallExecutionStatus.CHOICE_ACTION_NOT_CLICKABLE,
            listOf(
                node(text = "语音通话", enabled = false),
                node(handle = 2, text = "视频通话"),
            ) to
                WechatSemanticCallExecutionStatus.CHOICE_ACTION_NOT_CLICKABLE,
            listOf(
                node(text = "语音通话", clickable = false),
                node(handle = 2, text = "视频通话"),
            ) to
                WechatSemanticCallExecutionStatus.CHOICE_ACTION_NOT_CLICKABLE,
        )

        cases.forEachIndexed { index, (actions, expected) ->
            val harness = harness(planId = "choice_action_$index")
            openProfile(harness)
            harness.port.choiceEvidence = choiceEvidence(actions, now.plusNanos(2))

            val result = harness.executor.executeCallChoice(
                harness.plan.planId,
                WECHAT_PACKAGE,
                WECHAT_VERSION,
                now.plusNanos(2),
                harness.port,
            )

            assertEquals(expected, result?.status)
            assertEquals(0, harness.port.choiceClickCount)
            assertEquals(ChannelResult.FAILED, result?.finalDeliveryReportOrNull()?.result)
        }
    }

    @Test
    fun wrongPackageExpiresRequestAndCannotBeReplayed() {
        val harness = harness()

        val wrongPackage = harness.executor.executeContactProfile(
            harness.plan.planId,
            "other.package",
            WECHAT_VERSION,
            now.plusNanos(1),
            harness.port,
        )
        val replay = openProfile(harness)

        assertEquals(WechatSemanticCallExecutionStatus.PACKAGE_MISMATCH, wrongPackage?.status)
        assertNull(replay)
        assertEquals(0, harness.port.profileClickCount)
    }

    @Test
    fun timeoutAndInterruptFailClosedWithoutClicks() {
        val timedOut = harness(planId = "timed_out")
        val timeoutResult = timedOut.executor.executeContactProfile(
            timedOut.plan.planId,
            WECHAT_PACKAGE,
            WECHAT_VERSION,
            now.plusSeconds(5),
            timedOut.port,
        )
        val interrupted = harness(planId = "interrupted")
        val interruptResult = interrupted.broker.interrupt(interrupted.plan.planId)

        assertEquals(WechatSemanticCallExecutionStatus.WINDOW_EXPIRED, timeoutResult?.status)
        assertEquals(WechatSemanticCallExecutionStatus.INTERRUPTED, interruptResult?.status)
        assertEquals(0, timedOut.port.profileClickCount)
        assertNull(openProfile(interrupted))
    }

    @Test
    fun versionChangeBetweenStagesStopsBeforeFinalClick() {
        val harness = harness()
        openProfile(harness)

        val result = harness.executor.executeCallChoice(
            harness.plan.planId,
            WECHAT_PACKAGE,
            "8.0.77",
            now.plusNanos(2),
            harness.port,
        )

        assertEquals(WechatSemanticCallExecutionStatus.WECHAT_VERSION_CHANGED, result?.status)
        assertEquals(0, harness.port.choiceClickCount)
        assertEquals(ChannelResult.FAILED, result?.finalDeliveryReportOrNull()?.result)
    }

    @Test
    fun choiceAtExactThreeSecondDeadlineFailsBeforeClick() {
        val harness = harness(planId = "choice_exact_deadline")
        openProfile(harness)
        val exactDeadline = now.plusSeconds(3).plusNanos(1)
        harness.port.choiceEvidence = choiceEvidence(
            listOf(
                node(text = "语音通话"),
                node(handle = 2, text = "视频通话"),
            ),
            exactDeadline,
        )

        val result = harness.executor.executeCallChoice(
            harness.plan.planId,
            WECHAT_PACKAGE,
            WECHAT_VERSION,
            now.plusNanos(2),
            harness.port,
        )

        assertEquals(WechatSemanticCallExecutionStatus.WINDOW_EXPIRED, result?.status)
        assertEquals(0, harness.port.choiceClickCount)
    }

    @Test
    fun audioReleaseFailureConsumesChoiceAndPublishesFiniteFailure() = runTest {
        val harness = harness(planId = "audio_release_failed")
        openProfile(harness)
        assertTrue(harness.broker.isCallChoicePending())
        val emitted = async(start = CoroutineStart.UNDISPATCHED) {
            harness.broker.outcomes.take(1).toList()
        }

        val failed = harness.broker.failCallChoiceAudioRelease()
        val replay = harness.executor.executeNext(
            WECHAT_PACKAGE,
            WECHAT_VERSION,
            now.plusNanos(2),
            harness.port,
        )

        assertEquals(WechatSemanticCallExecutionStatus.AUDIO_RELEASE_FAILED, failed?.status)
        assertEquals(ChannelResult.FAILED, failed?.finalDeliveryReportOrNull()?.result)
        assertEquals(
            WechatSemanticCallExecutionStatus.AUDIO_RELEASE_FAILED,
            emitted.await().single().status,
        )
        assertFalse(harness.broker.isCallChoicePending())
        assertNull(replay)
        assertEquals(0, harness.port.choiceClickCount)
        assertEquals(0, harness.port.choiceReadCount)
    }

    @Test
    fun rejectedClicksAndRepeatedEventsNeverRetry() {
        val profileRejected = harness(planId = "profile_rejected")
        profileRejected.port.profileClickAccepted = false
        val profileResult = openProfile(profileRejected)
        val profileReplay = openProfile(profileRejected)

        assertEquals(
            WechatSemanticCallExecutionStatus.PROFILE_CLICK_REJECTED,
            profileResult?.status,
        )
        assertNull(profileReplay)
        assertEquals(1, profileRejected.port.profileClickCount)

        val choiceRejected = harness(planId = "choice_rejected")
        openProfile(choiceRejected)
        choiceRejected.port.choiceClickAccepted = false
        val choiceResult = choiceRejected.executor.executeCallChoice(
            choiceRejected.plan.planId,
            WECHAT_PACKAGE,
            WECHAT_VERSION,
            now.plusNanos(2),
            choiceRejected.port,
        )
        val choiceReplay = choiceRejected.executor.executeCallChoice(
            choiceRejected.plan.planId,
            WECHAT_PACKAGE,
            WECHAT_VERSION,
            now.plusNanos(3),
            choiceRejected.port,
        )

        assertEquals(
            WechatSemanticCallExecutionStatus.CHOICE_CLICK_REJECTED,
            choiceResult?.status,
        )
        assertNull(choiceReplay)
        assertEquals(1, choiceRejected.port.choiceClickCount)
    }

    @Test
    fun consumedPlanCannotBeArmedAgainInSameProcess() {
        val broker = WechatSemanticCallExecutionBroker()
        val admission = input()

        assertTrue(broker.arm(admission).armed)
        broker.clear()
        val replay = broker.arm(admission)

        assertFalse(replay.armed)
        assertEquals(WechatSemanticCallDenial.PLAN_ALREADY_CONSUMED, replay.denial)
    }

    @Test
    fun executeNextNeedsNoPlanIdAndPublishesBothStageOutcomes() = runTest {
        val harness = harness(planId = "flow_plan")
        val emitted = async(start = CoroutineStart.UNDISPATCHED) {
            harness.broker.outcomes.take(2).toList()
        }

        val profile = harness.executor.executeNext(
            WECHAT_PACKAGE,
            WECHAT_VERSION,
            now.plusNanos(1),
            harness.port,
        )
        val choice = harness.executor.executeNext(
            WECHAT_PACKAGE,
            WECHAT_VERSION,
            now.plusNanos(2),
            harness.port,
        )

        assertEquals(WechatSemanticCallExecutionStatus.AWAITING_CALL_CHOICE, profile?.status)
        assertEquals(WechatSemanticCallExecutionStatus.HANDED_TO_WECHAT, choice?.status)
        assertEquals(
            listOf(
                WechatSemanticCallExecutionStatus.AWAITING_CALL_CHOICE,
                WechatSemanticCallExecutionStatus.HANDED_TO_WECHAT,
            ),
            emitted.await().map { it.status },
        )
    }

    @Test
    fun explicitExpiryPublishesAndPreventsLaterExecution() = runTest {
        val harness = harness(planId = "expire_flow")
        val emitted = async(start = CoroutineStart.UNDISPATCHED) {
            harness.broker.outcomes.take(1).toList()
        }

        val expired = harness.broker.expire(harness.plan.planId, now.plusSeconds(5))
        val late = harness.executor.executeNext(
            WECHAT_PACKAGE,
            WECHAT_VERSION,
            now.plusSeconds(5),
            harness.port,
        )

        assertEquals(WechatSemanticCallExecutionStatus.WINDOW_EXPIRED, expired?.status)
        assertEquals(
            WechatSemanticCallExecutionStatus.WINDOW_EXPIRED,
            emitted.await().single().status,
        )
        assertNull(late)
        assertEquals(0, harness.port.profileClickCount)
    }

    @Test
    fun duplicateArmDoesNotDiscardAlreadyArmedFirstExecution() {
        val harness = harness()
        val duplicate = harness.broker.arm(
            input(plan = harness.plan, verifiedProof = verifiedProof(harness.plan)),
        )

        val firstExecution = openProfile(harness)

        assertEquals(WechatSemanticCallDenial.PLAN_ALREADY_CONSUMED, duplicate.denial)
        assertEquals(
            WechatSemanticCallExecutionStatus.AWAITING_CALL_CHOICE,
            firstExecution?.status,
        )
        assertEquals(1, harness.port.profileClickCount)
    }

    @Test
    fun invalidDifferentPlanCannotDiscardAlreadyArmedExecution() {
        val harness = harness(planId = "existing_valid_plan")
        val invalidPlan = plan(planId = "invalid_new_plan")

        val rejected = harness.broker.arm(
            input(
                plan = invalidPlan,
                verifiedProof = verifiedProof(invalidPlan),
                semanticRuleVersion = "wrong-semantic-rule",
            ),
        )
        val original = openProfile(harness)

        assertEquals(WechatSemanticCallDenial.PLAN_ALREADY_CONSUMED, rejected.denial)
        assertEquals(
            WechatSemanticCallExecutionStatus.AWAITING_CALL_CHOICE,
            original?.status,
        )
        assertEquals(1, harness.port.profileClickCount)
    }

    private fun harness(
        action: WechatActionType = WechatActionType.START_VOICE_CALL,
        planId: String = PLAN_ID,
    ): Harness {
        val plan = plan(action = action, planId = planId)
        val broker = WechatSemanticCallExecutionBroker()
        val arm = broker.arm(
            input(
                plan = plan,
                session = session(action.intent()),
                verifiedProof = verifiedProof(plan),
            ),
        )
        assertTrue(arm.armed)
        return Harness(
            broker = broker,
            executor = WechatSemanticCallExecutionBroker.Executor(broker),
            plan = plan,
            port = FakeUiPort(
                profileEvidence = profileEvidence(),
                choiceEvidence = choiceEvidence(
                    listOf(
                        node(text = action.choiceText()),
                        node(handle = 99, text = action.oppositeChoiceText()),
                    ),
                    now.plusNanos(2),
                ),
                initialTime = now,
            ),
        )
    }

    private fun openProfile(harness: Harness): WechatSemanticCallExecutionOutcome? =
        harness.executor.executeContactProfile(
            harness.plan.planId,
            WECHAT_PACKAGE,
            WECHAT_VERSION,
            now.plusNanos(1),
            harness.port,
        )

    private fun input(
        plan: WechatActionPlan = plan(),
        session: TaskSession = session(plan.action.intent()),
        taskCurrent: Boolean = true,
        verifiedProof: VerifiedWechatTargetLocatorProof? = verifiedProof(plan),
        currentWechatVersion: String = plan.targetLocatorProof.wechatVersion,
        semanticRuleVersion: String = WechatSemanticCallContract.RULE_VERSION,
        planId: String? = null,
        wechatVersion: String? = null,
    ): WechatSemanticCallAdmissionInput {
        val actualPlan = if (planId != null || wechatVersion != null) {
            plan(
                action = plan.action,
                planId = planId ?: plan.planId,
                wechatVersion = wechatVersion ?: plan.targetLocatorProof.wechatVersion,
            )
        } else {
            plan
        }
        val actualProof = if (actualPlan !== plan && verifiedProof != null) {
            verifiedProof(actualPlan)
        } else {
            verifiedProof
        }
        return WechatSemanticCallAdmissionInput(
            plan = actualPlan,
            session = session,
            taskCurrent = taskCurrent,
            verifiedTargetLocatorProof = actualProof,
            currentWechatVersion = wechatVersion ?: currentWechatVersion,
            semanticRuleVersion = semanticRuleVersion,
            now = now,
        )
    }

    private fun plan(
        action: WechatActionType = WechatActionType.START_VOICE_CALL,
        planId: String = PLAN_ID,
        wechatVersion: String = WECHAT_VERSION,
        locatorVersion: String = WechatSemanticCallContract.LOCATOR_VERSION,
        expiresAt: OffsetDateTime = now.plusSeconds(30),
        audioObjectId: String? = null,
    ): WechatActionPlan = WechatActionPlan(
        planId = planId,
        action = action,
        contactId = CONTACT_ID,
        summaryHash = SUMMARY_HASH,
        minimumRuleVersion = WechatSemanticCallContract.RULE_VERSION,
        expiresAt = expiresAt,
        targetSearchLocator = "wxid_demo123",
        targetLocatorProof = WechatTargetLocatorProof(
            proofVersion = WechatTargetLocatorProof.ProofVersion.WECHAT_LOCATOR_PROOF_V1,
            keyId = "test-key",
            contactVersion = 7,
            wechatVersion = wechatVersion,
            locatorVersion = locatorVersion,
            salt = "0".repeat(32),
            targetLocatorSha256 = LOCATOR_DIGEST,
            issuedAt = now,
            expiresAt = expiresAt,
            signature = "A".repeat(86),
        ),
        audioObjectId = audioObjectId,
    )

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

    private fun session(
        intent: Intent,
        state: TaskState = TaskState.EXECUTING,
        summaryHash: String = SUMMARY_HASH,
        contactId: String = CONTACT_ID,
    ): TaskSession = TaskSession(
        sessionId = "33333333-3333-3333-3333-333333333333",
        sessionVersion = 4,
        state = state,
        candidates = emptyList(),
        allowedActions = emptySet<AllowedAction>(),
        expiresAt = now.plusMinutes(1),
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
            contact = MatchedContact(contactId, "测试亲友", "测试称呼"),
        ),
        spokenSummary = "测试摘要",
        summaryHash = summaryHash,
        channelResult = null,
    )

    private fun profileEvidence(
        locators: List<WechatSemanticLocatorEvidence> = listOf(locator()),
        actions: List<WechatSemanticActionNodeEvidence> = listOf(node()),
    ): WechatSemanticContactProfileEvidence = WechatSemanticContactProfileEvidence(
        locatorCandidates = locators,
        callEntryCandidates = actions,
        capturedAt = now.plusNanos(1),
    )

    private fun choiceEvidence(
        actions: List<WechatSemanticActionNodeEvidence>,
        capturedAt: OffsetDateTime,
    ): WechatSemanticCallChoiceEvidence = WechatSemanticCallChoiceEvidence(
        actionCandidates = actions,
        capturedAt = capturedAt,
    )

    private fun locator(
        digest: String = LOCATOR_DIGEST,
        visible: Boolean = true,
    ): WechatSemanticLocatorEvidence = WechatSemanticLocatorEvidence(digest, visible)

    private fun node(
        handle: Int = 1,
        text: String = "音视频通话",
        visible: Boolean = true,
        enabled: Boolean = true,
        clickable: Boolean = true,
    ): WechatSemanticActionNodeEvidence = WechatSemanticActionNodeEvidence(
        handle = handle,
        text = text,
        visibleToUser = visible,
        enabled = enabled,
        selfClickable = clickable,
    )

    private fun WechatActionType.intent(): Intent = when (this) {
        WechatActionType.START_VOICE_CALL -> Intent.VOICE_CALL
        WechatActionType.START_VIDEO_CALL -> Intent.VIDEO_CALL
        WechatActionType.SEND_AUDIO_AND_TEXT -> Intent.SEND_MESSAGE
    }

    private fun WechatActionType.choiceText(): String = when (this) {
        WechatActionType.START_VOICE_CALL -> "语音通话"
        WechatActionType.START_VIDEO_CALL -> "视频通话"
        WechatActionType.SEND_AUDIO_AND_TEXT -> error("测试不支持消息动作")
    }

    private fun WechatActionType.oppositeChoiceText(): String = when (this) {
        WechatActionType.START_VOICE_CALL -> "视频通话"
        WechatActionType.START_VIDEO_CALL -> "语音通话"
        WechatActionType.SEND_AUDIO_AND_TEXT -> error("测试不支持消息动作")
    }

    private data class Harness(
        val broker: WechatSemanticCallExecutionBroker,
        val executor: WechatSemanticCallExecutionBroker.Executor,
        val plan: WechatActionPlan,
        val port: FakeUiPort,
    )

    private class FakeUiPort(
        var profileEvidence: WechatSemanticContactProfileEvidence?,
        var choiceEvidence: WechatSemanticCallChoiceEvidence?,
        initialTime: OffsetDateTime,
    ) : WechatSemanticCallUiPort {
        var profileClickAccepted = true
        var choiceClickAccepted = true
        var profileClickCount = 0
        var choiceClickCount = 0
        var choiceReadCount = 0
        private var observedTime = initialTime
        val locatorSalts = mutableListOf<String>()
        val clickedChoiceHandles = mutableListOf<Int>()

        override fun readContactProfileEvidence(
            locatorSalt: String,
            now: OffsetDateTime,
        ): WechatSemanticContactProfileEvidence? {
            locatorSalts += locatorSalt
            return profileEvidence?.also { evidence ->
                if (evidence.capturedAt.isAfter(observedTime)) observedTime = evidence.capturedAt
            }
        }

        override fun clickContactProfileCallEntry(handle: Int): Boolean {
            profileClickCount++
            return profileClickAccepted
        }

        override fun readCallChoiceEvidence(
            now: OffsetDateTime,
        ): WechatSemanticCallChoiceEvidence? {
            choiceReadCount++
            return choiceEvidence?.also { evidence ->
                if (evidence.capturedAt.isAfter(observedTime)) observedTime = evidence.capturedAt
            }
        }

        override fun clickCallChoice(handle: Int): Boolean {
            choiceClickCount++
            clickedChoiceHandles += handle
            return choiceClickAccepted
        }

        override fun currentTime(): OffsetDateTime = observedTime
    }

    private companion object {
        const val PLAN_ID = "wp_semantic_call_plan"
        const val CONTACT_ID = "11111111-1111-1111-1111-111111111111"
        const val SUMMARY_HASH =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val LOCATOR_DIGEST =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val WECHAT_VERSION = "8.0.76"
    }
}
