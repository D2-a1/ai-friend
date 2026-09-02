package com.aifriend.feature.wechat

import com.aifriend.contract.model.Intent
import com.aifriend.contract.model.TaskSession
import com.aifriend.contract.model.TaskState
import com.aifriend.contract.model.WechatActionPlan
import com.aifriend.contract.model.WechatActionType
import java.security.MessageDigest
import java.time.Duration
import java.time.OffsetDateTime
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 自用 MVP 的微信语义通话契约。
 *
 * 规则版本描述本机严格语义算法，不绑定某个微信版本；微信版本只与当前动作计划中已经
 * Ed25519 验证的定位证明精确匹配。既有签名页面规则仍可作为未来加固层，但不是本路径前置条件。
 */
object WechatSemanticCallContract {
    const val RULE_VERSION = "wechat-semantic-call-v1"
    const val LOCATOR_VERSION = "wechat-invitation-id-v1"
    const val WECHAT_PACKAGE = "com.tencent.mm"
}

/** 语义通话准入输入；可信证明只能来自 [WechatTargetLocatorProofVerifier]。 */
data class WechatSemanticCallAdmissionInput(
    val plan: WechatActionPlan,
    val session: TaskSession,
    val taskCurrent: Boolean,
    val verifiedTargetLocatorProof: VerifiedWechatTargetLocatorProof?,
    val currentWechatVersion: String,
    val semanticRuleVersion: String,
    val now: OffsetDateTime,
) {
    override fun toString(): String =
        "WechatSemanticCallAdmissionInput(taskCurrent=$taskCurrent, " +
            "verifiedTargetLocatorProof=${verifiedTargetLocatorProof != null}, " +
            "currentWechatVersion=<redacted>, semanticRuleVersion=$semanticRuleVersion, now=$now)"
}

/** 准入失败原因不包含计划、联系人、定位摘要或页面文字。 */
enum class WechatSemanticCallDenial {
    TASK_NOT_CURRENT,
    SESSION_NOT_EXECUTING,
    SESSION_EXPIRED,
    PLAN_EXPIRED,
    PLAN_INVALID,
    SUMMARY_MISMATCH,
    CONTACT_MISMATCH,
    INTENT_ACTION_MISMATCH,
    ACTION_PAYLOAD_INVALID,
    SEMANTIC_RULE_MISMATCH,
    VERIFIED_PROOF_MISSING,
    VERIFIED_PROOF_INVALID,
    LOCATOR_VERSION_MISMATCH,
    WECHAT_VERSION_MISMATCH,
    PLAN_ALREADY_CONSUMED,
}

data class WechatSemanticCallArmResult(
    val armed: Boolean,
    val denial: WechatSemanticCallDenial?,
) {
    companion object {
        fun armed(): WechatSemanticCallArmResult = WechatSemanticCallArmResult(true, null)

        fun denied(reason: WechatSemanticCallDenial): WechatSemanticCallArmResult =
            WechatSemanticCallArmResult(false, reason)
    }
}

/**
 * 固定查询得到的瞬时微信号候选。
 *
 * 端口必须使用当前计划盐计算摘要并立即清零原始微信号；核心从不接收原始微信号。
 */
data class WechatSemanticLocatorEvidence(
    val targetLocatorSha256: String,
    val visibleToUser: Boolean,
) {
    override fun toString(): String =
        "WechatSemanticLocatorEvidence(targetLocatorSha256=<redacted>, " +
            "visibleToUser=$visibleToUser)"
}

/** 固定文本查询得到的瞬时节点事实；handle 只允许在同一次执行调用内使用。 */
data class WechatSemanticActionNodeEvidence(
    val handle: Int,
    val text: String,
    val visibleToUser: Boolean,
    val enabled: Boolean,
    val selfClickable: Boolean,
) {
    init {
        require(handle >= 0) { "节点句柄无效" }
        require(text.length <= MAXIMUM_FIXED_TEXT_LENGTH) { "固定节点文字过长" }
    }

    override fun toString(): String =
        "WechatSemanticActionNodeEvidence(handle=<redacted>, text=$text, " +
            "visibleToUser=$visibleToUser, enabled=$enabled, selfClickable=$selfClickable)"

    private companion object {
        const val MAXIMUM_FIXED_TEXT_LENGTH = 16
    }
}

/** 联系人资料页只允许包含微信号摘要候选和“音视频通话”固定查询结果。 */
data class WechatSemanticContactProfileEvidence(
    val locatorCandidates: List<WechatSemanticLocatorEvidence>,
    val callEntryCandidates: List<WechatSemanticActionNodeEvidence>,
    val capturedAt: OffsetDateTime,
) {
    override fun toString(): String =
        "WechatSemanticContactProfileEvidence(locatorCandidates=<redacted>, " +
            "callEntryCandidateCount=${callEntryCandidates.size}, capturedAt=$capturedAt)"
}

/** 通话类型弹层只允许包含“语音通话/视频通话”固定查询结果。 */
data class WechatSemanticCallChoiceEvidence(
    val actionCandidates: List<WechatSemanticActionNodeEvidence>,
    val capturedAt: OffsetDateTime,
) {
    override fun toString(): String =
        "WechatSemanticCallChoiceEvidence(actionCandidateCount=${actionCandidates.size}, " +
            "capturedAt=$capturedAt)"
}

/**
 * Android 无障碍适配器需要实现的最小端口。
 *
 * 实现不得猜父节点、坐标或手势；读取后只允许点击本次证据中的自身可点击节点一次。
 */
interface WechatSemanticCallUiPort {
    fun readContactProfileEvidence(
        locatorSalt: String,
        now: OffsetDateTime,
    ): WechatSemanticContactProfileEvidence?

    fun clickContactProfileCallEntry(handle: Int): Boolean

    fun readCallChoiceEvidence(now: OffsetDateTime): WechatSemanticCallChoiceEvidence?

    fun clickCallChoice(handle: Int): Boolean

    /** Binder 查询完成后、真正点击前读取当前时间，避免沿用事件到达时的旧时间。 */
    fun currentTime(): OffsetDateTime
}

enum class WechatSemanticCallExecutionStatus {
    AWAITING_CALL_CHOICE,
    HANDED_TO_WECHAT,
    PACKAGE_MISMATCH,
    WECHAT_VERSION_CHANGED,
    WINDOW_EXPIRED,
    INTERRUPTED,
    PROFILE_EVIDENCE_UNAVAILABLE,
    PROFILE_TARGET_NOT_UNIQUE,
    PROFILE_TARGET_NOT_VISIBLE,
    PROFILE_TARGET_MISMATCH,
    PROFILE_ACTION_NOT_UNIQUE,
    PROFILE_ACTION_NOT_CLICKABLE,
    PROFILE_CLICK_REJECTED,
    CHOICE_EVIDENCE_UNAVAILABLE,
    CHOICE_TYPE_CONFLICT,
    CHOICE_ACTION_NOT_UNIQUE,
    CHOICE_ACTION_NOT_CLICKABLE,
    CHOICE_CLICK_REJECTED,
    AUDIO_RELEASE_FAILED,
    CALIBRATED_PROFILE_CHANGED,
    CALIBRATED_WECHAT_NOT_FOREGROUND,
    CALIBRATED_CLIPBOARD_UNAVAILABLE,
    CALIBRATED_GESTURE_REJECTED,
    CALIBRATED_EXECUTION_INTERRUPTED,
}

/** 阶段一成功只表示等待类型弹层；只有最终点击被系统接受才形成 OPENED 交接结果。 */
data class WechatSemanticCallExecutionOutcome(
    val planId: String,
    val action: WechatActionType,
    val status: WechatSemanticCallExecutionStatus,
) {
    init {
        require(action.isCallAction()) { "语义通话结果只能用于通话动作" }
    }

    fun finalDeliveryReportOrNull(): WechatCallChoiceDeliveryReport? = when (status) {
        WechatSemanticCallExecutionStatus.AWAITING_CALL_CHOICE -> null
        WechatSemanticCallExecutionStatus.HANDED_TO_WECHAT ->
            WechatCallChoiceActionOutcome(
                planId = planId,
                action = action,
                status = WechatCallChoiceActionStatus.CLICK_REQUEST_ACCEPTED,
                callStartedConfirmationArmed = false,
            ).toDeliveryReport()
        else -> failedWechatCallDeliveryReport(
            action = action,
            evidenceCode = "SEMANTIC_CALL_${status.name}",
        )
    }

    override fun toString(): String =
        "WechatSemanticCallExecutionOutcome(planId=<redacted>, action=$action, status=$status)"
}

private data class WechatSemanticProfileRequest(
    val token: Long,
    val planId: String,
    val action: WechatActionType,
    val wechatVersion: String,
    val targetLocatorSha256: String,
    val locatorSalt: String,
    val openedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val hardExpiresAt: OffsetDateTime,
) {
    override fun toString(): String =
        "WechatSemanticProfileRequest(token=<redacted>, planId=<redacted>, action=$action, " +
            "wechatVersion=<redacted>, targetLocatorSha256=<redacted>, locatorSalt=<redacted>, " +
            "openedAt=$openedAt, expiresAt=$expiresAt, hardExpiresAt=$hardExpiresAt)"
}

private data class WechatSemanticChoiceRequest(
    val token: Long,
    val planId: String,
    val action: WechatActionType,
    val wechatVersion: String,
    val openedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
) {
    override fun toString(): String =
        "WechatSemanticChoiceRequest(token=<redacted>, planId=<redacted>, action=$action, " +
            "wechatVersion=<redacted>, openedAt=$openedAt, expiresAt=$expiresAt)"
}

private sealed interface WechatSemanticBrokerState {
    val planId: String
    val action: WechatActionType

    data class ProfileArmed(val request: WechatSemanticProfileRequest) : WechatSemanticBrokerState {
        override val planId: String = request.planId
        override val action: WechatActionType = request.action
    }

    data class ProfileLeased(val request: WechatSemanticProfileRequest) : WechatSemanticBrokerState {
        override val planId: String = request.planId
        override val action: WechatActionType = request.action
    }

    data class ChoiceArmed(val request: WechatSemanticChoiceRequest) : WechatSemanticBrokerState {
        override val planId: String = request.planId
        override val action: WechatActionType = request.action
    }

    data class ChoiceLeased(val request: WechatSemanticChoiceRequest) : WechatSemanticBrokerState {
        override val planId: String = request.planId
        override val action: WechatActionType = request.action
    }
}

private sealed interface WechatSemanticTakeResult<out T> {
    data class Lease<T>(val request: T) : WechatSemanticTakeResult<T>
    data class Rejected(val outcome: WechatSemanticCallExecutionOutcome) :
        WechatSemanticTakeResult<Nothing>
    data object Ignored : WechatSemanticTakeResult<Nothing>
}

private enum class WechatSemanticExecutionStep {
    CONTACT_PROFILE,
    CALL_CHOICE,
}

private data class WechatSemanticActiveStep(
    val planId: String,
    val step: WechatSemanticExecutionStep,
    val action: WechatActionType,
    val locatorSalt: String?,
    val openedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
)

/**
 * 当前进程两阶段一次性门闩。
 *
 * 一个计划编号一旦成功武装就在当前进程记为已消费；阶段请求在读取页面和点击前先租出，
 * 重复事件无法再次获得请求。错误包、版本变化、超时和中断都会清除整条链。
 */
@Singleton
class WechatSemanticCallExecutionBroker @Inject constructor() {
    private val mutableOutcomes = MutableSharedFlow<WechatSemanticCallExecutionOutcome>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var state: WechatSemanticBrokerState? = null
    private var nextToken = 1L
    private val consumedPlans = LinkedHashMap<String, OffsetDateTime>()

    val outcomes: SharedFlow<WechatSemanticCallExecutionOutcome> =
        mutableOutcomes.asSharedFlow()

    @Synchronized
    fun arm(input: WechatSemanticCallAdmissionInput): WechatSemanticCallArmResult {
        consumedPlans.entries.removeAll { (_, expiresAt) -> !expiresAt.isAfter(input.now) }
        if (consumedPlans.containsKey(input.plan.planId)) {
            return WechatSemanticCallArmResult.denied(
                WechatSemanticCallDenial.PLAN_ALREADY_CONSUMED,
            )
        }
        if (state != null) {
            return WechatSemanticCallArmResult.denied(
                WechatSemanticCallDenial.PLAN_ALREADY_CONSUMED,
            )
        }
        val denial = WechatSemanticCallAdmission.evaluate(input)
        if (denial != null) return WechatSemanticCallArmResult.denied(denial)

        val proof = checkNotNull(input.verifiedTargetLocatorProof)
        val expiresAt = minInstant(
            input.now.plus(PROFILE_WINDOW),
            input.plan.expiresAt,
            input.session.expiresAt,
            proof.expiresAt,
        )
        if (!expiresAt.isAfter(input.now)) {
            return WechatSemanticCallArmResult.denied(WechatSemanticCallDenial.PLAN_EXPIRED)
        }
        val request = WechatSemanticProfileRequest(
            token = nextToken++,
            planId = input.plan.planId,
            action = input.plan.action,
            wechatVersion = input.currentWechatVersion,
            targetLocatorSha256 = proof.targetLocatorSha256,
            locatorSalt = input.plan.targetLocatorProof.salt,
            openedAt = input.now,
            expiresAt = expiresAt,
            hardExpiresAt = minInstant(
                input.plan.expiresAt,
                input.session.expiresAt,
                proof.expiresAt,
            ),
        )
        if (consumedPlans.size >= MAXIMUM_REMEMBERED_PLANS) {
            return WechatSemanticCallArmResult.denied(WechatSemanticCallDenial.PLAN_INVALID)
        }
        consumedPlans[input.plan.planId] = proof.expiresAt
        state = WechatSemanticBrokerState.ProfileArmed(request)
        return WechatSemanticCallArmResult.armed()
    }

    @Synchronized
    fun interrupt(planId: String? = null): WechatSemanticCallExecutionOutcome? {
        val current = state ?: return null
        if (planId != null && current.planId != planId) return null
        state = null
        return outcome(
            current.planId,
            current.action,
            WechatSemanticCallExecutionStatus.INTERRUPTED,
        ).also(::publish)
    }

    /** 无微信事件时由 ViewModel 的单次定时器结束当前阶段；不会重武装或自动重试。 */
    @Synchronized
    fun expire(
        planId: String,
        now: OffsetDateTime,
    ): WechatSemanticCallExecutionOutcome? {
        val current = state ?: return null
        if (current.planId != planId) return null
        val (openedAt, expiresAt) = when (current) {
            is WechatSemanticBrokerState.ProfileArmed ->
                current.request.openedAt to current.request.expiresAt
            is WechatSemanticBrokerState.ChoiceArmed ->
                current.request.openedAt to current.request.expiresAt
            is WechatSemanticBrokerState.ProfileLeased,
            is WechatSemanticBrokerState.ChoiceLeased,
            -> return null
        }
        if (!now.isBefore(openedAt) && expiresAt.isAfter(now)) return null
        state = null
        return outcome(
            current.planId,
            current.action,
            WechatSemanticCallExecutionStatus.WINDOW_EXPIRED,
        ).also(::publish)
    }

    fun publish(outcome: WechatSemanticCallExecutionOutcome) {
        mutableOutcomes.tryEmit(outcome)
    }

    /** 辅助服务只在最终通话类型点击前释放守护麦克风。 */
    @Synchronized
    fun isCallChoicePending(): Boolean =
        state is WechatSemanticBrokerState.ChoiceArmed

    /** 守护麦克风无法释放时结束选择阶段；不读取页面，也不尝试点击。 */
    @Synchronized
    fun failCallChoiceAudioRelease(): WechatSemanticCallExecutionOutcome? {
        val current = state as? WechatSemanticBrokerState.ChoiceArmed ?: return null
        state = null
        return outcome(
            current.planId,
            current.action,
            WechatSemanticCallExecutionStatus.AUDIO_RELEASE_FAILED,
        ).also(::publish)
    }

    @Synchronized
    fun clear() {
        state = null
    }

    @Synchronized
    private fun activeStep(): WechatSemanticActiveStep? = when (val current = state) {
        is WechatSemanticBrokerState.ProfileArmed ->
            WechatSemanticActiveStep(
                planId = current.planId,
                step = WechatSemanticExecutionStep.CONTACT_PROFILE,
                action = current.action,
                locatorSalt = current.request.locatorSalt,
                openedAt = current.request.openedAt,
                expiresAt = current.request.expiresAt,
            )
        is WechatSemanticBrokerState.ChoiceArmed ->
            WechatSemanticActiveStep(
                planId = current.planId,
                step = WechatSemanticExecutionStep.CALL_CHOICE,
                action = current.action,
                locatorSalt = null,
                openedAt = current.request.openedAt,
                expiresAt = current.request.expiresAt,
            )
        else -> null
    }

    @Synchronized
    private fun takeProfile(
        planId: String,
        packageName: String,
        currentWechatVersion: String,
        now: OffsetDateTime,
    ): WechatSemanticTakeResult<WechatSemanticProfileRequest> {
        val current = state as? WechatSemanticBrokerState.ProfileArmed
            ?: return WechatSemanticTakeResult.Ignored
        if (current.planId != planId) return WechatSemanticTakeResult.Ignored
        envelopeFailure(
            current.planId,
            current.action,
            packageName,
            current.request.wechatVersion,
            current.request.openedAt,
            current.request.expiresAt,
            currentWechatVersion,
            now,
        )?.let {
            state = null
            return WechatSemanticTakeResult.Rejected(it)
        }
        state = WechatSemanticBrokerState.ProfileLeased(current.request)
        return WechatSemanticTakeResult.Lease(current.request)
    }

    @Synchronized
    private fun completeProfile(
        request: WechatSemanticProfileRequest,
        accepted: Boolean,
        now: OffsetDateTime,
    ): WechatSemanticCallExecutionOutcome? {
        val current = state as? WechatSemanticBrokerState.ProfileLeased ?: return null
        if (current.request.token != request.token) return null
        if (!accepted) {
            state = null
            return null
        }
        val expiresAt = minInstant(now.plus(CHOICE_WINDOW), request.hardExpiresAt)
        if (!expiresAt.isAfter(now)) {
            state = null
            return outcome(request, WechatSemanticCallExecutionStatus.WINDOW_EXPIRED)
        }
        state = WechatSemanticBrokerState.ChoiceArmed(
            WechatSemanticChoiceRequest(
                token = nextToken++,
                planId = request.planId,
                action = request.action,
                wechatVersion = request.wechatVersion,
                openedAt = now,
                expiresAt = expiresAt,
            ),
        )
        return outcome(request, WechatSemanticCallExecutionStatus.AWAITING_CALL_CHOICE)
    }

    @Synchronized
    private fun takeChoice(
        planId: String,
        packageName: String,
        currentWechatVersion: String,
        now: OffsetDateTime,
    ): WechatSemanticTakeResult<WechatSemanticChoiceRequest> {
        val current = state as? WechatSemanticBrokerState.ChoiceArmed
            ?: return WechatSemanticTakeResult.Ignored
        if (current.planId != planId) return WechatSemanticTakeResult.Ignored
        envelopeFailure(
            current.planId,
            current.action,
            packageName,
            current.request.wechatVersion,
            current.request.openedAt,
            current.request.expiresAt,
            currentWechatVersion,
            now,
        )?.let {
            state = null
            return WechatSemanticTakeResult.Rejected(it)
        }
        state = WechatSemanticBrokerState.ChoiceLeased(current.request)
        return WechatSemanticTakeResult.Lease(current.request)
    }

    @Synchronized
    private fun completeChoice(request: WechatSemanticChoiceRequest) {
        val current = state as? WechatSemanticBrokerState.ChoiceLeased ?: return
        if (current.request.token == request.token) state = null
    }

    @Synchronized
    private fun failProfile(request: WechatSemanticProfileRequest) {
        val current = state as? WechatSemanticBrokerState.ProfileLeased ?: return
        if (current.request.token == request.token) state = null
    }

    @Synchronized
    private fun failChoice(request: WechatSemanticChoiceRequest) {
        val current = state as? WechatSemanticBrokerState.ChoiceLeased ?: return
        if (current.request.token == request.token) state = null
    }

    private fun envelopeFailure(
        planId: String,
        action: WechatActionType,
        packageName: String,
        expectedWechatVersion: String,
        openedAt: OffsetDateTime,
        expiresAt: OffsetDateTime,
        currentWechatVersion: String,
        now: OffsetDateTime,
    ): WechatSemanticCallExecutionOutcome? = when {
        packageName != WechatSemanticCallContract.WECHAT_PACKAGE ->
            outcome(planId, action, WechatSemanticCallExecutionStatus.PACKAGE_MISMATCH)
        currentWechatVersion != expectedWechatVersion ->
            outcome(planId, action, WechatSemanticCallExecutionStatus.WECHAT_VERSION_CHANGED)
        now.isBefore(openedAt) || !expiresAt.isAfter(now) ->
            outcome(planId, action, WechatSemanticCallExecutionStatus.WINDOW_EXPIRED)
        else -> null
    }

    private fun outcome(
        request: WechatSemanticProfileRequest,
        status: WechatSemanticCallExecutionStatus,
    ): WechatSemanticCallExecutionOutcome = outcome(request.planId, request.action, status)

    private fun outcome(
        planId: String,
        action: WechatActionType,
        status: WechatSemanticCallExecutionStatus,
    ): WechatSemanticCallExecutionOutcome =
        WechatSemanticCallExecutionOutcome(planId, action, status)

    private companion object {
        val PROFILE_WINDOW: Duration = Duration.ofSeconds(5)
        val CHOICE_WINDOW: Duration = Duration.ofSeconds(3)
        const val MAXIMUM_REMEMBERED_PLANS = 32
    }

    @Singleton
    class Executor @Inject constructor(
        private val broker: WechatSemanticCallExecutionBroker,
    ) {
        /**
         * 页面尚在加载时不租出一次性请求。只检查固定查询是否已经形成完整、可点击的页面形状；
         * 不比较联系人摘要，因此打开错误联系人仍会在正式执行时消费请求并失败关闭。
         */
        fun isCurrentStepReady(
            now: OffsetDateTime,
            uiPort: WechatSemanticCallUiPort,
        ): Boolean {
            val active = broker.activeStep() ?: return false
            if (now.isBefore(active.openedAt) || !active.expiresAt.isAfter(now)) return false
            return when (active.step) {
                WechatSemanticExecutionStep.CONTACT_PROFILE -> {
                    val evidence = runCatching {
                        uiPort.readContactProfileEvidence(checkNotNull(active.locatorSalt), now)
                    }.getOrNull() ?: return false
                    val locatorReady = evidence.locatorCandidates.singleOrNull()
                        ?.visibleToUser == true
                    val entryReady = evidence.callEntryCandidates
                        .filter { it.text == CONTACT_PROFILE_CALL_ENTRY_TEXT }
                        .singleOrNull()
                        ?.isReadyForClick() == true
                    locatorReady && entryReady &&
                        capturedInWindow(evidence.capturedAt, active.openedAt, active.expiresAt)
                }
                WechatSemanticExecutionStep.CALL_CHOICE -> {
                    val evidence = runCatching {
                        uiPort.readCallChoiceEvidence(now)
                    }.getOrNull() ?: return false
                    val voiceReady = evidence.actionCandidates
                        .filter { it.text == "语音通话" }
                        .singleOrNull()
                        ?.isReadyForClick() == true
                    val videoReady = evidence.actionCandidates
                        .filter { it.text == "视频通话" }
                        .singleOrNull()
                        ?.isReadyForClick() == true
                    voiceReady && videoReady &&
                        capturedInWindow(evidence.capturedAt, active.openedAt, active.expiresAt)
                }
            }
        }

        /**
         * 无障碍事件无需知道计划编号；只消费当前阶段的一次性请求并自动发布有限结果。
         */
        fun executeNext(
            packageName: String,
            currentWechatVersion: String,
            now: OffsetDateTime,
            uiPort: WechatSemanticCallUiPort,
        ): WechatSemanticCallExecutionOutcome? {
            val active = broker.activeStep() ?: return null
            return when (active.step) {
                WechatSemanticExecutionStep.CONTACT_PROFILE -> executeContactProfile(
                    active.planId,
                    packageName,
                    currentWechatVersion,
                    now,
                    uiPort,
                )
                WechatSemanticExecutionStep.CALL_CHOICE -> executeCallChoice(
                    active.planId,
                    packageName,
                    currentWechatVersion,
                    now,
                    uiPort,
                )
            }
        }

        fun executeContactProfile(
            planId: String,
            packageName: String,
            currentWechatVersion: String,
            now: OffsetDateTime,
            uiPort: WechatSemanticCallUiPort,
        ): WechatSemanticCallExecutionOutcome? =
            executeContactProfileOnce(
                planId,
                packageName,
                currentWechatVersion,
                now,
                uiPort,
            ).also { outcome -> outcome?.let(broker::publish) }

        private fun executeContactProfileOnce(
            planId: String,
            packageName: String,
            currentWechatVersion: String,
            now: OffsetDateTime,
            uiPort: WechatSemanticCallUiPort,
        ): WechatSemanticCallExecutionOutcome? {
            val request = when (
                val taken = broker.takeProfile(
                    planId,
                    packageName,
                    currentWechatVersion,
                    now,
                )
            ) {
                is WechatSemanticTakeResult.Lease -> taken.request
                is WechatSemanticTakeResult.Rejected -> return taken.outcome
                WechatSemanticTakeResult.Ignored -> return null
            }
            val evidence = runCatching {
                uiPort.readContactProfileEvidence(request.locatorSalt, now)
            }.getOrNull()
                ?: return failProfile(
                    request,
                    WechatSemanticCallExecutionStatus.PROFILE_EVIDENCE_UNAVAILABLE,
                )
            val checkedAt = uiPort.currentTime()
            if (checkedAt.isBefore(request.openedAt) || !request.expiresAt.isAfter(checkedAt)) {
                return failProfile(
                    request,
                    WechatSemanticCallExecutionStatus.WINDOW_EXPIRED,
                )
            }
            if (!capturedAtOrBefore(evidence.capturedAt, request.openedAt, checkedAt)) {
                return failProfile(
                    request,
                    WechatSemanticCallExecutionStatus.PROFILE_EVIDENCE_UNAVAILABLE,
                )
            }
            if (evidence.locatorCandidates.size != 1) {
                return failProfile(
                    request,
                    WechatSemanticCallExecutionStatus.PROFILE_TARGET_NOT_UNIQUE,
                )
            }
            val locator = evidence.locatorCandidates.single()
            if (!locator.visibleToUser) {
                return failProfile(
                    request,
                    WechatSemanticCallExecutionStatus.PROFILE_TARGET_NOT_VISIBLE,
                )
            }
            if (!constantTimeSha256Equals(
                    locator.targetLocatorSha256,
                    request.targetLocatorSha256,
                )
            ) {
                return failProfile(
                    request,
                    WechatSemanticCallExecutionStatus.PROFILE_TARGET_MISMATCH,
                )
            }
            val exactActions = evidence.callEntryCandidates.filter {
                it.text == CONTACT_PROFILE_CALL_ENTRY_TEXT
            }
            if (exactActions.size != 1) {
                return failProfile(
                    request,
                    WechatSemanticCallExecutionStatus.PROFILE_ACTION_NOT_UNIQUE,
                )
            }
            val actionNode = exactActions.single()
            if (!actionNode.visibleToUser || !actionNode.enabled || !actionNode.selfClickable) {
                return failProfile(
                    request,
                    WechatSemanticCallExecutionStatus.PROFILE_ACTION_NOT_CLICKABLE,
                )
            }
            val beforeClick = uiPort.currentTime()
            if (beforeClick.isBefore(request.openedAt) || !request.expiresAt.isAfter(beforeClick)) {
                return failProfile(
                    request,
                    WechatSemanticCallExecutionStatus.WINDOW_EXPIRED,
                )
            }
            val accepted = runCatching {
                uiPort.clickContactProfileCallEntry(actionNode.handle)
            }.getOrDefault(false)
            if (!accepted) {
                return failProfile(
                    request,
                    WechatSemanticCallExecutionStatus.PROFILE_CLICK_REJECTED,
                )
            }
            return broker.completeProfile(
                request,
                accepted = true,
                now = uiPort.currentTime(),
            )
                ?: outcome(request, WechatSemanticCallExecutionStatus.INTERRUPTED)
        }

        fun executeCallChoice(
            planId: String,
            packageName: String,
            currentWechatVersion: String,
            now: OffsetDateTime,
            uiPort: WechatSemanticCallUiPort,
        ): WechatSemanticCallExecutionOutcome? =
            executeCallChoiceOnce(
                planId,
                packageName,
                currentWechatVersion,
                now,
                uiPort,
            ).also { outcome -> outcome?.let(broker::publish) }

        private fun executeCallChoiceOnce(
            planId: String,
            packageName: String,
            currentWechatVersion: String,
            now: OffsetDateTime,
            uiPort: WechatSemanticCallUiPort,
        ): WechatSemanticCallExecutionOutcome? {
            val request = when (
                val taken = broker.takeChoice(
                    planId,
                    packageName,
                    currentWechatVersion,
                    now,
                )
            ) {
                is WechatSemanticTakeResult.Lease -> taken.request
                is WechatSemanticTakeResult.Rejected -> return taken.outcome
                WechatSemanticTakeResult.Ignored -> return null
            }
            val evidence = runCatching { uiPort.readCallChoiceEvidence(now) }.getOrNull()
                ?: return failChoice(
                    request,
                    WechatSemanticCallExecutionStatus.CHOICE_EVIDENCE_UNAVAILABLE,
                )
            val checkedAt = uiPort.currentTime()
            if (checkedAt.isBefore(request.openedAt) || !request.expiresAt.isAfter(checkedAt)) {
                return failChoice(
                    request,
                    WechatSemanticCallExecutionStatus.WINDOW_EXPIRED,
                )
            }
            if (!capturedAtOrBefore(evidence.capturedAt, request.openedAt, checkedAt)) {
                return failChoice(
                    request,
                    WechatSemanticCallExecutionStatus.CHOICE_EVIDENCE_UNAVAILABLE,
                )
            }
            val expected = request.action.callChoiceText()
            val opposite = request.action.oppositeCallChoiceText()
            val expectedActions = evidence.actionCandidates.filter { it.text == expected }
            val oppositeActions = evidence.actionCandidates.filter { it.text == opposite }
            if (expectedActions.size != 1) {
                return failChoice(
                    request,
                    WechatSemanticCallExecutionStatus.CHOICE_ACTION_NOT_UNIQUE,
                )
            }
            if (oppositeActions.size != 1) {
                return failChoice(
                    request,
                    WechatSemanticCallExecutionStatus.CHOICE_TYPE_CONFLICT,
                )
            }
            val actionNode = expectedActions.single()
            if (!actionNode.visibleToUser || !actionNode.enabled || !actionNode.selfClickable) {
                return failChoice(
                    request,
                    WechatSemanticCallExecutionStatus.CHOICE_ACTION_NOT_CLICKABLE,
                )
            }
            val beforeClick = uiPort.currentTime()
            if (beforeClick.isBefore(request.openedAt) || !request.expiresAt.isAfter(beforeClick)) {
                return failChoice(
                    request,
                    WechatSemanticCallExecutionStatus.WINDOW_EXPIRED,
                )
            }
            val accepted = runCatching { uiPort.clickCallChoice(actionNode.handle) }
                .getOrDefault(false)
            broker.completeChoice(request)
            return outcome(
                request,
                if (accepted) {
                    WechatSemanticCallExecutionStatus.HANDED_TO_WECHAT
                } else {
                    WechatSemanticCallExecutionStatus.CHOICE_CLICK_REJECTED
                },
            )
        }

        private fun failProfile(
            request: WechatSemanticProfileRequest,
            status: WechatSemanticCallExecutionStatus,
        ): WechatSemanticCallExecutionOutcome {
            broker.failProfile(request)
            return outcome(request, status)
        }

        private fun failChoice(
            request: WechatSemanticChoiceRequest,
            status: WechatSemanticCallExecutionStatus,
        ): WechatSemanticCallExecutionOutcome {
            broker.failChoice(request)
            return outcome(request, status)
        }

        private fun outcome(
            request: WechatSemanticProfileRequest,
            status: WechatSemanticCallExecutionStatus,
        ): WechatSemanticCallExecutionOutcome =
            WechatSemanticCallExecutionOutcome(request.planId, request.action, status)

        private fun outcome(
            request: WechatSemanticChoiceRequest,
            status: WechatSemanticCallExecutionStatus,
        ): WechatSemanticCallExecutionOutcome =
            WechatSemanticCallExecutionOutcome(request.planId, request.action, status)
    }
}

/** 对外使用的简洁执行器名称；实际实例仍由 Hilt 构造并绑定同一个单例 broker。 */
typealias WechatSemanticCallExecutionExecutor = WechatSemanticCallExecutionBroker.Executor

private object WechatSemanticCallAdmission {
    fun evaluate(input: WechatSemanticCallAdmissionInput): WechatSemanticCallDenial? {
        val plan = input.plan
        val session = input.session
        val now = input.now
        if (!input.taskCurrent) return WechatSemanticCallDenial.TASK_NOT_CURRENT
        if (session.state != TaskState.EXECUTING) {
            return WechatSemanticCallDenial.SESSION_NOT_EXECUTING
        }
        if (!session.expiresAt.isAfter(now)) return WechatSemanticCallDenial.SESSION_EXPIRED
        if (!plan.expiresAt.isAfter(now)) return WechatSemanticCallDenial.PLAN_EXPIRED
        if (plan.planId.isBlank() || plan.contactId.isBlank() || plan.summaryHash.isBlank()) {
            return WechatSemanticCallDenial.PLAN_INVALID
        }
        if (plan.summaryHash != session.summaryHash) {
            return WechatSemanticCallDenial.SUMMARY_MISMATCH
        }
        val understanding = session.understanding
        if (understanding?.contact?.id != plan.contactId) {
            return WechatSemanticCallDenial.CONTACT_MISMATCH
        }
        val intentMatches = when (plan.action) {
            WechatActionType.START_VOICE_CALL -> understanding.intent == Intent.VOICE_CALL
            WechatActionType.START_VIDEO_CALL -> understanding.intent == Intent.VIDEO_CALL
            WechatActionType.SEND_AUDIO_AND_TEXT -> false
        }
        if (!intentMatches) return WechatSemanticCallDenial.INTENT_ACTION_MISMATCH
        if (plan.audioObjectId != null) return WechatSemanticCallDenial.ACTION_PAYLOAD_INVALID
        if (input.semanticRuleVersion != WechatSemanticCallContract.RULE_VERSION ||
            plan.minimumRuleVersion != input.semanticRuleVersion
        ) {
            return WechatSemanticCallDenial.SEMANTIC_RULE_MISMATCH
        }

        val proof = input.verifiedTargetLocatorProof
            ?: return WechatSemanticCallDenial.VERIFIED_PROOF_MISSING
        val rawProof = plan.targetLocatorProof
        if (proof.planId != plan.planId || proof.contactId != plan.contactId ||
            proof.contactVersion < 0 || proof.contactVersion != rawProof.contactVersion ||
            proof.keyId.isBlank() || proof.keyId != rawProof.keyId ||
            proof.wechatVersion != rawProof.wechatVersion ||
            proof.locatorVersion != rawProof.locatorVersion ||
            proof.targetLocatorSha256 != rawProof.targetLocatorSha256 ||
            proof.issuedAt.toInstant() != rawProof.issuedAt.toInstant() ||
            proof.expiresAt.toInstant() != rawProof.expiresAt.toInstant() ||
            proof.expiresAt.toInstant() != plan.expiresAt.toInstant() ||
            proof.issuedAt.isAfter(now.plus(PROOF_CLOCK_TOLERANCE)) ||
            !proof.expiresAt.isAfter(now) || !proof.expiresAt.isAfter(proof.issuedAt) ||
            Duration.between(proof.issuedAt, proof.expiresAt) > MAXIMUM_PROOF_LIFETIME ||
            !proof.targetLocatorSha256.matches(SHA256_HEX) ||
            !rawProof.salt.matches(SALT_HEX)
        ) {
            return WechatSemanticCallDenial.VERIFIED_PROOF_INVALID
        }
        if (proof.locatorVersion != WechatSemanticCallContract.LOCATOR_VERSION) {
            return WechatSemanticCallDenial.LOCATOR_VERSION_MISMATCH
        }
        if (!input.currentWechatVersion.matches(VERSION_TOKEN) ||
            input.currentWechatVersion != proof.wechatVersion
        ) {
            return WechatSemanticCallDenial.WECHAT_VERSION_MISMATCH
        }
        return null
    }

    private val PROOF_CLOCK_TOLERANCE: Duration = Duration.ofSeconds(2)
    private val MAXIMUM_PROOF_LIFETIME: Duration = Duration.ofSeconds(30)
    private val SHA256_HEX = Regex("[0-9a-f]{64}")
    private val SALT_HEX = Regex("[0-9a-f]{32}")
    private val VERSION_TOKEN = Regex("[^\\s]{1,100}")
}

private fun capturedInWindow(
    capturedAt: OffsetDateTime,
    openedAt: OffsetDateTime,
    upperBoundExclusive: OffsetDateTime,
): Boolean = !capturedAt.isBefore(openedAt) && capturedAt.isBefore(upperBoundExclusive)

private fun capturedAtOrBefore(
    capturedAt: OffsetDateTime,
    openedAt: OffsetDateTime,
    checkedAt: OffsetDateTime,
): Boolean = !capturedAt.isBefore(openedAt) && !capturedAt.isAfter(checkedAt)

private fun WechatSemanticActionNodeEvidence.isReadyForClick(): Boolean =
    visibleToUser && enabled && selfClickable

private fun constantTimeSha256Equals(left: String, right: String): Boolean {
    if (!left.matches(Regex("[0-9a-f]{64}")) || !right.matches(Regex("[0-9a-f]{64}"))) {
        return false
    }
    return MessageDigest.isEqual(left.encodeToByteArray(), right.encodeToByteArray())
}

private fun WechatActionType.callChoiceText(): String = when (this) {
    WechatActionType.START_VOICE_CALL -> "语音通话"
    WechatActionType.START_VIDEO_CALL -> "视频通话"
    WechatActionType.SEND_AUDIO_AND_TEXT -> error("消息动作没有通话类型")
}

private fun WechatActionType.oppositeCallChoiceText(): String = when (this) {
    WechatActionType.START_VOICE_CALL -> "视频通话"
    WechatActionType.START_VIDEO_CALL -> "语音通话"
    WechatActionType.SEND_AUDIO_AND_TEXT -> error("消息动作没有通话类型")
}

private fun minInstant(vararg values: OffsetDateTime): OffsetDateTime =
    values.minBy { it.toInstant() }

private const val CONTACT_PROFILE_CALL_ENTRY_TEXT = "音视频通话"
