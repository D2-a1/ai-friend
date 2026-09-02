package com.aifriend.feature.wechat

import com.aifriend.contract.model.TaskSession
import com.aifriend.contract.model.WechatActionPlan
import com.aifriend.contract.model.WechatActionType
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.SharedFlow

/** 提供当前任务瞬时微信上下文；不得从持久化恢复旧计划或旧页面。 */
interface WechatExecutionContextProvider {
    fun current(plan: WechatActionPlan, now: OffsetDateTime): WechatExecutionContext
}

/**
 * 只暴露当前安装环境已通过签名规则包和能力矩阵校验的能力快照。
 *
 * 能力来自已签名规则包；页面快照只允许从当前五秒观察门闩一次性消费。定位证明仍由独立
 * 验签端口提供，任何缺项均保持失败关闭。
 */
@Singleton
class SignedWechatExecutionContextProvider @Inject constructor(
    private val rulePackageRegistry: WechatRulePackageRegistry,
    private val observationBroker: WechatPageObservationBroker,
) : WechatExecutionContextProvider {
    override fun current(
        plan: WechatActionPlan,
        now: OffsetDateTime,
    ): WechatExecutionContext = WechatExecutionContext(
        capability = rulePackageRegistry.currentCapability() ?: WechatCapabilitySnapshot.disabled(),
        pageSnapshot = observationBroker.consume(plan.planId, now),
        targetLocatorProof = null,
    )
}

/** 当前任务动作计划与本地安全上下文的准入编排器；只可开启观察或一次性动作请求。 */
@Singleton
class WechatExecutionCoordinator @Inject constructor(
    private val admission: WechatExecutionAdmission,
    private val contextProvider: WechatExecutionContextProvider,
    private val locatorProofVerifier: WechatTargetLocatorProofVerifier,
    private val semanticCallExecutionBroker: WechatSemanticCallExecutionBroker,
    private val calibratedCallExecutionBroker: WechatCalibratedCallExecutionBroker,
    private val runtimeVersionProvider: WechatRuntimeVersionProvider,
    private val calibrationFingerprintProvider: WechatCalibrationFingerprintProvider,
    private val calibrationProfileRegistry: WechatCalibrationProfileRegistry,
    private val observationBroker: WechatPageObservationBroker,
    private val actionBroker: WechatVerifiedContactProfileActionBroker,
    private val directChatTransitionBroker: WechatDirectChatTransitionBroker,
    private val callChoiceActionBroker: WechatCallChoiceActionBroker,
    private val callStartedTransitionBroker: WechatCallStartedTransitionBroker,
) {
    val publishedPlans: SharedFlow<String> = observationBroker.publishedPlans
    val actionOutcomes: SharedFlow<WechatVerifiedContactProfileActionOutcome> =
        actionBroker.outcomes
    val directChatTransitionOutcomes: SharedFlow<WechatDirectChatTransitionOutcome> =
        directChatTransitionBroker.outcomes
    val callChoiceActionOutcomes: SharedFlow<WechatCallChoiceActionOutcome> =
        callChoiceActionBroker.outcomes
    val callStartedTransitionOutcomes: SharedFlow<WechatCallStartedTransitionOutcome> =
        callStartedTransitionBroker.outcomes
    val semanticCallOutcomes: SharedFlow<WechatSemanticCallExecutionOutcome> =
        calibratedCallExecutionBroker.outcomes

    fun evaluate(
        plan: WechatActionPlan,
        session: TaskSession,
        taskCurrent: Boolean,
    ): WechatExecutionAdmissionResult {
        val now = OffsetDateTime.now()
        val proof = locatorProofVerifier.verify(plan, now)
        if (plan.action.isSemanticCall()) {
            return evaluateSemanticCall(plan, session, taskCurrent, proof, now)
        }
        val context = contextProvider.current(plan, now).copy(targetLocatorProof = proof)
        val result = admission.evaluate(
            plan = plan,
            session = session,
            taskCurrent = taskCurrent,
            context = context,
            now = now,
        )
        if (result.denial == WechatExecutionDenial.PAGE_SNAPSHOT_MISSING && proof != null) {
            observationBroker.arm(plan, context.capability, proof, session.expiresAt, now)
        } else if (result.allowed && proof != null) {
            actionBroker.arm(plan, context.capability, proof, now)
        }
        return result
    }

    fun clear() {
        observationBroker.clear()
        actionBroker.clear()
        directChatTransitionBroker.clear()
        callChoiceActionBroker.clear()
        callStartedTransitionBroker.clear()
        semanticCallExecutionBroker.clear()
        calibratedCallExecutionBroker.clear()
    }

    fun expireDirectChatTransition(planId: String) {
        directChatTransitionBroker.expire(planId, OffsetDateTime.now())
    }

    fun expireCallStartedTransition(planId: String) {
        callStartedTransitionBroker.expire(planId, OffsetDateTime.now())
    }

    fun expireSemanticCall(planId: String) {
        val now = OffsetDateTime.now()
        calibratedCallExecutionBroker.expire(planId, now)
            ?: semanticCallExecutionBroker.expire(planId, now)
    }

    fun currentWechatVersion(): String? = runCatching {
        runtimeVersionProvider.readCurrentVersion()
    }.getOrNull()

    private fun evaluateSemanticCall(
        plan: WechatActionPlan,
        session: TaskSession,
        taskCurrent: Boolean,
        proof: VerifiedWechatTargetLocatorProof?,
        now: OffsetDateTime,
    ): WechatExecutionAdmissionResult {
        val wechatVersion = currentWechatVersion()
            ?: return WechatExecutionAdmissionResult.deny(
                WechatExecutionDenial.WECHAT_VERSION_MISMATCH,
            )
        val calibrationKey = calibrationFingerprintProvider.current(wechatVersion)
            ?: return WechatExecutionAdmissionResult.deny(
                WechatExecutionDenial.CALIBRATION_PROFILE_MISSING,
            )
        val calibrationProfile = calibrationProfileRegistry.findExact(calibrationKey)
        if (calibrationProfile == null) {
            return WechatExecutionAdmissionResult.deny(
                WechatExecutionDenial.CALIBRATION_PROFILE_MISSING,
            )
        }
        val armResult = semanticCallExecutionBroker.arm(
            WechatSemanticCallAdmissionInput(
                plan = plan,
                session = session,
                taskCurrent = taskCurrent,
                verifiedTargetLocatorProof = proof,
                currentWechatVersion = wechatVersion,
                semanticRuleVersion = WechatSemanticCallContract.RULE_VERSION,
                now = now,
            ),
        )
        if (armResult.armed) {
            // 旧 broker 只复用已经充分测试的签名计划准入；坐标 broker 接管唯一实际执行请求。
            semanticCallExecutionBroker.clear()
            if (calibratedCallExecutionBroker.arm(plan, calibrationProfile, wechatVersion, now)) {
                return WechatExecutionAdmissionResult.allow()
            }
            return WechatExecutionAdmissionResult.deny(
                WechatExecutionDenial.ACTION_PAYLOAD_INVALID,
            )
        }
        return WechatExecutionAdmissionResult.deny(
            checkNotNull(armResult.denial).toExecutionDenial(),
        )
    }

    private fun WechatSemanticCallDenial.toExecutionDenial(): WechatExecutionDenial = when (this) {
        WechatSemanticCallDenial.TASK_NOT_CURRENT -> WechatExecutionDenial.TASK_NOT_CURRENT
        WechatSemanticCallDenial.SESSION_NOT_EXECUTING ->
            WechatExecutionDenial.SESSION_NOT_EXECUTING
        WechatSemanticCallDenial.SESSION_EXPIRED -> WechatExecutionDenial.SESSION_EXPIRED
        WechatSemanticCallDenial.PLAN_EXPIRED,
        WechatSemanticCallDenial.PLAN_ALREADY_CONSUMED,
        -> WechatExecutionDenial.PLAN_EXPIRED
        WechatSemanticCallDenial.SUMMARY_MISMATCH -> WechatExecutionDenial.SUMMARY_MISMATCH
        WechatSemanticCallDenial.CONTACT_MISMATCH -> WechatExecutionDenial.CONTACT_MISMATCH
        WechatSemanticCallDenial.INTENT_ACTION_MISMATCH ->
            WechatExecutionDenial.INTENT_ACTION_MISMATCH
        WechatSemanticCallDenial.PLAN_INVALID,
        WechatSemanticCallDenial.ACTION_PAYLOAD_INVALID,
        -> WechatExecutionDenial.ACTION_PAYLOAD_INVALID
        WechatSemanticCallDenial.SEMANTIC_RULE_MISMATCH ->
            WechatExecutionDenial.RULE_VERSION_UNSUPPORTED
        WechatSemanticCallDenial.VERIFIED_PROOF_MISSING ->
            WechatExecutionDenial.TARGET_LOCATOR_PROOF_MISSING
        WechatSemanticCallDenial.VERIFIED_PROOF_INVALID,
        WechatSemanticCallDenial.LOCATOR_VERSION_MISMATCH,
        -> WechatExecutionDenial.TARGET_LOCATOR_PROOF_INVALID
        WechatSemanticCallDenial.WECHAT_VERSION_MISMATCH ->
            WechatExecutionDenial.WECHAT_VERSION_MISMATCH
    }

    private fun WechatActionType.isSemanticCall(): Boolean = when (this) {
        WechatActionType.START_VOICE_CALL,
        WechatActionType.START_VIDEO_CALL,
        -> true
        WechatActionType.SEND_AUDIO_AND_TEXT -> false
    }
}
