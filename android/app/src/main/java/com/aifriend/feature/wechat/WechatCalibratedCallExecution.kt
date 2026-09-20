package com.aifriend.feature.wechat

import com.aifriend.contract.model.WechatActionPlan
import com.aifriend.contract.model.WechatActionType
import java.time.Duration
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull

/** 坐标执行端口；实现只能执行当前请求的一次顺序动作，不得重试。 */
interface WechatCalibratedCallUiPort {
    fun currentTime(): OffsetDateTime

    fun currentFingerprint(): WechatCalibrationProfileKey?

    fun isWechatForeground(): Boolean

    /** 只记录固定阶段枚举，不包含微信号、联系人、消息内容或页面文字。 */
    fun recordStage(stage: WechatCalibratedCallStage) = Unit

    fun recordMessageStage(stage: WechatMessageSelectionStage) = Unit

    suspend fun tap(point: WechatCalibrationPixelPoint): Boolean

    suspend fun longPress(point: WechatCalibrationPixelPoint): Boolean

    /**
     * 只向当前微信窗口唯一、可见且可编辑的输入框写入签名计划中的微信号。
     * 实现不得记录、持久化或返回输入框中的文字。
     */
    fun setSearchText(value: CharArray): Boolean = false

    /** 分享页必须同时回读精确查询、唯一微信号结果及校准点所属结果行。 */
    suspend fun verifyMessageSearchResult(value: CharArray, point: WechatCalibrationPixelPoint): Boolean = false

    /** 最后点击前验证唯一发送按钮和取消按钮；未知页面不执行。 */
    suspend fun verifyMessageSendButton(point: WechatCalibrationPixelPoint): Boolean = false

    fun setSensitiveSearchClipboard(value: CharArray): Boolean

    fun clearSearchClipboard()

    fun readContactProfileEvidence(
        locatorSalt: String,
        now: OffsetDateTime,
    ): WechatSemanticContactProfileEvidence? = null

    /**
     * 节点树完全不可读时允许实现使用同屏、内存内的视觉证据；部分或冲突节点证据不得覆盖。
     */
    suspend fun readContactProfileEvidenceWithVisualFallback(
        locatorSalt: String,
        now: OffsetDateTime,
    ): WechatSemanticContactProfileEvidence? = readContactProfileEvidence(locatorSalt, now)

    fun readCallChoiceEvidence(now: OffsetDateTime): WechatSemanticCallChoiceEvidence? = null

    /** 仅在节点树没有提供任何通话类型证据时允许使用视觉证据。 */
    suspend fun readCallChoiceEvidenceWithVisualFallback(
        now: OffsetDateTime,
    ): WechatSemanticCallChoiceEvidence? = readCallChoiceEvidence(now)

    /** 释放尚未消费的一次性页面节点。 */
    fun releasePageEvidence() = Unit

    suspend fun waitForUi(duration: Duration)

    suspend fun releaseAudioBeforeCall(): Boolean
}

enum class WechatCalibratedCallStage {
    SEARCH_OPENED,
    SEARCH_INPUT_FOCUSED,
    SEARCH_INPUT_ACCEPTED,
    SEARCH_RESULT_TAPPED,
    CHAT_CONTACT_AVATAR_TAPPED,
    CHAT_INFO_MENU_TAPPED,
    CHAT_INFO_CONTACT_AVATAR_TAPPED,
    CONTACT_PROFILE_VERIFIED,
    CALL_ENTRY_TAPPED,
    CALL_CHOICE_VERIFIED,
    AUDIO_RELEASED,
}

class WechatCalibratedCallRequest internal constructor(
    internal val token: Long,
    val planId: String,
    val action: WechatActionType,
    val wechatVersion: String,
    val profile: WechatCalibrationProfile,
    internal val targetSearchLocator: CharArray,
    internal val targetLocatorSha256: String,
    internal val locatorSalt: String,
    internal val capability: WechatCapabilitySnapshot,
    val openedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
) {
    override fun toString(): String =
        "WechatCalibratedCallRequest(token=<redacted>, planId=<redacted>, action=$action, " +
            "wechatVersion=<redacted>, profileKey=${profile.key}, " +
            "targetSearchLocator=<redacted>, openedAt=$openedAt, expiresAt=$expiresAt)"
}

sealed interface WechatCalibratedCallTakeResult {
    data class Lease(val request: WechatCalibratedCallRequest) : WechatCalibratedCallTakeResult

    data class Rejected(val outcome: WechatSemanticCallExecutionOutcome) :
        WechatCalibratedCallTakeResult

    data object Ignored : WechatCalibratedCallTakeResult
}

/** 当前进程的一次性坐标通话请求；计划取出后无论成功失败都不能再次取得。 */
@Singleton
class WechatCalibratedCallExecutionBroker @Inject constructor() {
    private val mutableOutcomes = MutableSharedFlow<WechatSemanticCallExecutionOutcome>(
        replay = 0,
        extraBufferCapacity = 8,
    )
    private var state: State? = null
    private var nextToken = 1L

    val outcomes: SharedFlow<WechatSemanticCallExecutionOutcome> = mutableOutcomes.asSharedFlow()

    @Synchronized
    fun arm(
        plan: WechatActionPlan,
        profile: WechatCalibrationProfile,
        wechatVersion: String,
        capability: WechatCapabilitySnapshot,
        now: OffsetDateTime,
    ): Boolean {
        if (state != null ||
            (plan.action != WechatActionType.START_VOICE_CALL &&
                plan.action != WechatActionType.START_VIDEO_CALL) ||
            plan.planId.isBlank() || plan.targetSearchLocator.isBlank() ||
            !profile.supportsCall || profile.key.wechatVersion != wechatVersion || !plan.expiresAt.isAfter(now)
        ) {
            return false
        }
        val expiresAt = minOf(plan.expiresAt, now.plus(EXECUTION_WINDOW))
        if (!expiresAt.isAfter(now)) return false
        state = State.Armed(
            WechatCalibratedCallRequest(
                token = nextToken++,
                planId = plan.planId,
                action = plan.action,
                wechatVersion = wechatVersion,
                profile = profile,
                targetSearchLocator = plan.targetSearchLocator.toCharArray(),
                targetLocatorSha256 = plan.targetLocatorProof.targetLocatorSha256,
                locatorSalt = plan.targetLocatorProof.salt,
                capability = capability,
                openedAt = now,
                expiresAt = expiresAt,
            ),
        )
        return true
    }

    @Synchronized
    fun isPending(): Boolean = state != null

    @Synchronized
    fun isLeaseActive(token: Long): Boolean =
        (state as? State.Leased)?.request?.token == token

    @Synchronized
    fun take(
        packageName: String,
        currentWechatVersion: String,
        now: OffsetDateTime,
    ): WechatCalibratedCallTakeResult {
        val armed = state as? State.Armed ?: return WechatCalibratedCallTakeResult.Ignored
        val request = armed.request
        val failure = when {
            packageName != WechatSemanticCallContract.WECHAT_PACKAGE ->
                WechatSemanticCallExecutionStatus.PACKAGE_MISMATCH
            currentWechatVersion != request.wechatVersion ->
                WechatSemanticCallExecutionStatus.WECHAT_VERSION_CHANGED
            now.isBefore(request.openedAt) || !request.expiresAt.isAfter(now) ->
                WechatSemanticCallExecutionStatus.WINDOW_EXPIRED
            else -> null
        }
        if (failure != null) {
            state = null
            request.clearLocator()
            return WechatCalibratedCallTakeResult.Rejected(
                request.outcome(failure).also(::publish),
            )
        }
        state = State.Leased(request)
        return WechatCalibratedCallTakeResult.Lease(request)
    }

    @Synchronized
    fun complete(
        request: WechatCalibratedCallRequest,
        status: WechatSemanticCallExecutionStatus,
    ): WechatSemanticCallExecutionOutcome? {
        val leased = state as? State.Leased ?: return null
        if (leased.request.token != request.token) return null
        state = null
        request.clearLocator()
        return request.outcome(status).also(::publish)
    }

    @Synchronized
    fun expire(planId: String, now: OffsetDateTime): WechatSemanticCallExecutionOutcome? {
        val current = state ?: return null
        if (current.request.planId != planId || current is State.Leased ||
            (!now.isBefore(current.request.openedAt) && current.request.expiresAt.isAfter(now))
        ) {
            return null
        }
        state = null
        current.request.clearLocator()
        return current.request.outcome(WechatSemanticCallExecutionStatus.WINDOW_EXPIRED)
            .also(::publish)
    }

    @Synchronized
    fun interrupt(planId: String? = null): WechatSemanticCallExecutionOutcome? {
        val current = state ?: return null
        if (planId != null && current.request.planId != planId) return null
        state = null
        current.request.clearLocator()
        return current.request.outcome(WechatSemanticCallExecutionStatus.INTERRUPTED)
            .also(::publish)
    }

    @Synchronized
    fun clear() {
        state?.request?.clearLocator()
        state = null
    }

    private fun publish(outcome: WechatSemanticCallExecutionOutcome) {
        mutableOutcomes.tryEmit(outcome)
    }

    private sealed interface State {
        val request: WechatCalibratedCallRequest

        data class Armed(override val request: WechatCalibratedCallRequest) : State

        data class Leased(override val request: WechatCalibratedCallRequest) : State
    }

    private companion object {
        val EXECUTION_WINDOW: Duration = Duration.ofSeconds(25)
    }
}

/** 顺序执行一份完整档案；每个动作前重新核对时间、前台包和精确设备指纹。 */
@Singleton
class WechatCalibratedCallExecutionExecutor @Inject constructor(
    private val broker: WechatCalibratedCallExecutionBroker,
    private val callStartedTransitionBroker: WechatCallStartedTransitionBroker,
) {
    /** 单元测试便利构造；仍使用完整的通话页转移验证器。 */
    internal constructor(broker: WechatCalibratedCallExecutionBroker) : this(
        broker,
        WechatCallStartedTransitionBroker(),
    )

    suspend fun execute(
        packageName: String,
        currentWechatVersion: String,
        now: OffsetDateTime,
        uiPort: WechatCalibratedCallUiPort,
    ): WechatSemanticCallExecutionOutcome? {
        val request = when (val result = broker.take(packageName, currentWechatVersion, now)) {
            is WechatCalibratedCallTakeResult.Lease -> result.request
            is WechatCalibratedCallTakeResult.Rejected -> return result.outcome
            WechatCalibratedCallTakeResult.Ignored -> return null
        }
        val status = try {
            runSequence(request, uiPort)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            WechatSemanticCallExecutionStatus.CALIBRATED_EXECUTION_INTERRUPTED
        } finally {
            uiPort.clearSearchClipboard()
            uiPort.releasePageEvidence()
            request.targetSearchLocator.fill('\u0000')
        }
        if (status != WechatSemanticCallExecutionStatus.AWAITING_CALL_STARTED) {
            callStartedTransitionBroker.clear()
        }
        return broker.complete(request, status)
            ?: request.outcome(WechatSemanticCallExecutionStatus.INTERRUPTED)
    }

    private suspend fun runSequence(
        request: WechatCalibratedCallRequest,
        uiPort: WechatCalibratedCallUiPort,
    ): WechatSemanticCallExecutionStatus {
        suspend fun tap(
            target: WechatCalibrationTarget,
            waitAfter: Duration,
        ): WechatSemanticCallExecutionStatus? {
            validateRuntime(request, uiPort)?.let { return it }
            val point = request.profile.points.getValue(target).toPixels(
                request.profile.key.displayWidthPixels,
                request.profile.key.displayHeightPixels,
            )
            val accepted = withTimeoutOrNull(GESTURE_TIMEOUT.toMillis()) {
                uiPort.tap(point)
            } ?: false
            if (!accepted) {
                return WechatSemanticCallExecutionStatus.CALIBRATED_GESTURE_REJECTED
            }
            uiPort.waitForUi(waitAfter)
            return null
        }

        tap(WechatCalibrationTarget.HOME_SEARCH, PAGE_WAIT)?.let { return it }
        uiPort.recordStage(WechatCalibratedCallStage.SEARCH_OPENED)
        tap(WechatCalibrationTarget.GLOBAL_SEARCH_INPUT, INPUT_WAIT)?.let { return it }
        uiPort.recordStage(WechatCalibratedCallStage.SEARCH_INPUT_FOCUSED)
        val inputPoint = request.profile.points.getValue(
            WechatCalibrationTarget.GLOBAL_SEARCH_INPUT,
        ).toPixels(
            request.profile.key.displayWidthPixels,
            request.profile.key.displayHeightPixels,
        )
        validateRuntime(request, uiPort)?.let { return it }
        val directSetAccepted = uiPort.setSearchText(request.targetSearchLocator)
        if (!directSetAccepted) {
            if (!uiPort.setSensitiveSearchClipboard(request.targetSearchLocator)) {
                return WechatSemanticCallExecutionStatus.CALIBRATED_SEARCH_INPUT_UNAVAILABLE
            }
            val longPressAccepted = withTimeoutOrNull(GESTURE_TIMEOUT.toMillis()) {
                uiPort.longPress(inputPoint)
            } ?: false
            if (!longPressAccepted) {
                return WechatSemanticCallExecutionStatus.CALIBRATED_GESTURE_REJECTED
            }
            uiPort.waitForUi(PASTE_MENU_WAIT)
            tap(
                WechatCalibrationTarget.GLOBAL_SEARCH_PASTE,
                INPUT_VERIFY_WAIT,
            )?.let { return it }
        }
        uiPort.recordStage(WechatCalibratedCallStage.SEARCH_INPUT_ACCEPTED)
        uiPort.waitForUi(SEARCH_WAIT)
        uiPort.clearSearchClipboard()
        tap(WechatCalibrationTarget.SEARCH_RESULT, CHAT_PAGE_WAIT)?.let { return it }
        uiPort.recordStage(WechatCalibratedCallStage.SEARCH_RESULT_TAPPED)
        tap(WechatCalibrationTarget.CHAT_INFO_MENU, PAGE_WAIT)?.let { return it }
        uiPort.recordStage(WechatCalibratedCallStage.CHAT_INFO_MENU_TAPPED)
        tap(WechatCalibrationTarget.CHAT_INFO_CONTACT_AVATAR, PAGE_WAIT)?.let { return it }
        uiPort.recordStage(WechatCalibratedCallStage.CHAT_INFO_CONTACT_AVATAR_TAPPED)
        verifyContactProfile(request, uiPort)?.let { return it }
        uiPort.recordStage(WechatCalibratedCallStage.CONTACT_PROFILE_VERIFIED)
        tap(
            WechatCalibrationTarget.CONTACT_PROFILE_CALL_ENTRY,
            CALL_MENU_WAIT,
        )?.let { return it }
        uiPort.recordStage(WechatCalibratedCallStage.CALL_ENTRY_TAPPED)
        if (!isCallChoiceVerified(request, uiPort)) {
            return WechatSemanticCallExecutionStatus.CHOICE_EVIDENCE_UNAVAILABLE
        }
        uiPort.recordStage(WechatCalibratedCallStage.CALL_CHOICE_VERIFIED)
        validateRuntime(request, uiPort)?.let { return it }
        val callStartedEvidenceArmed = callStartedTransitionBroker.arm(
                planId = request.planId,
                action = request.action,
                capability = request.capability,
                now = uiPort.currentTime(),
            )
        if (!uiPort.releaseAudioBeforeCall()) {
            return WechatSemanticCallExecutionStatus.AUDIO_RELEASE_FAILED
        }
        uiPort.recordStage(WechatCalibratedCallStage.AUDIO_RELEASED)
        validateRuntime(request, uiPort)?.let { return it }
        val target = when (request.action) {
            WechatActionType.START_VOICE_CALL -> WechatCalibrationTarget.CALL_CHOICE_VOICE
            WechatActionType.START_VIDEO_CALL -> WechatCalibrationTarget.CALL_CHOICE_VIDEO
            WechatActionType.SEND_AUDIO_AND_TEXT ->
                return WechatSemanticCallExecutionStatus.CALIBRATED_EXECUTION_INTERRUPTED
        }
        return tap(target, Duration.ZERO) ?: if (callStartedEvidenceArmed) {
            WechatSemanticCallExecutionStatus.AWAITING_CALL_STARTED
        } else {
            WechatSemanticCallExecutionStatus.HANDED_TO_WECHAT
        }
    }

    private suspend fun verifyContactProfile(
        request: WechatCalibratedCallRequest,
        uiPort: WechatCalibratedCallUiPort,
    ): WechatSemanticCallExecutionStatus? {
        repeat(PROFILE_READ_ATTEMPTS) { attempt ->
            validateRuntime(request, uiPort)?.let { return it }
            val evidence = uiPort.readContactProfileEvidenceWithVisualFallback(
                request.locatorSalt,
                uiPort.currentTime(),
            )
            if (evidence == null) {
                if (attempt + 1 < PROFILE_READ_ATTEMPTS) {
                    uiPort.waitForUi(PROFILE_READ_INTERVAL)
                }
                return@repeat
            }
            val locator = evidence.locatorCandidates.singleOrNull()
                ?: return WechatSemanticCallExecutionStatus.PROFILE_TARGET_NOT_UNIQUE
            if (!locator.visibleToUser) {
                return WechatSemanticCallExecutionStatus.PROFILE_TARGET_NOT_VISIBLE
            }
            if (locator.targetLocatorSha256 != request.targetLocatorSha256) {
                return WechatSemanticCallExecutionStatus.PROFILE_TARGET_MISMATCH
            }
            val entry = evidence.callEntryCandidates
                .filter { it.text == CONTACT_PROFILE_CALL_ENTRY_TEXT }
                .singleOrNull()
                ?: return WechatSemanticCallExecutionStatus.PROFILE_ACTION_NOT_UNIQUE
            if (!entry.visibleToUser || !entry.enabled) {
                return WechatSemanticCallExecutionStatus.PROFILE_ACTION_NOT_CLICKABLE
            }
            uiPort.releasePageEvidence()
            return null
        }
        return WechatSemanticCallExecutionStatus.PROFILE_EVIDENCE_UNAVAILABLE
    }

    private suspend fun isCallChoiceVerified(
        request: WechatCalibratedCallRequest,
        uiPort: WechatCalibratedCallUiPort,
    ): Boolean {
        val evidence = uiPort.readCallChoiceEvidenceWithVisualFallback(
            uiPort.currentTime(),
        ) ?: return false
        val expectedText = when (request.action) {
            WechatActionType.START_VOICE_CALL -> VOICE_CALL_TEXT
            WechatActionType.START_VIDEO_CALL -> VIDEO_CALL_TEXT
            WechatActionType.SEND_AUDIO_AND_TEXT -> return false
        }
        val expected = evidence.actionCandidates.filter { it.text == expectedText }.singleOrNull()
            ?: return false
        val otherText = if (expectedText == VOICE_CALL_TEXT) VIDEO_CALL_TEXT else VOICE_CALL_TEXT
        if (evidence.actionCandidates.count { it.text == otherText } != 1) return false
        val verified = expected.visibleToUser && expected.enabled
        uiPort.releasePageEvidence()
        return verified
    }

    private fun validateRuntime(
        request: WechatCalibratedCallRequest,
        uiPort: WechatCalibratedCallUiPort,
    ): WechatSemanticCallExecutionStatus? {
        val now = uiPort.currentTime()
        return when {
            !broker.isLeaseActive(request.token) ->
                WechatSemanticCallExecutionStatus.CALIBRATED_EXECUTION_INTERRUPTED
            now.isBefore(request.openedAt) || !request.expiresAt.isAfter(now) ->
                WechatSemanticCallExecutionStatus.WINDOW_EXPIRED
            !uiPort.isWechatForeground() ->
                WechatSemanticCallExecutionStatus.CALIBRATED_WECHAT_NOT_FOREGROUND
            uiPort.currentFingerprint() != request.profile.key ->
                WechatSemanticCallExecutionStatus.CALIBRATED_PROFILE_CHANGED
            else -> null
        }
    }

    private companion object {
        val PAGE_WAIT: Duration = Duration.ofMillis(900)
        val INPUT_WAIT: Duration = Duration.ofMillis(300)
        val INPUT_VERIFY_WAIT: Duration = Duration.ofMillis(250)
        val PASTE_MENU_WAIT: Duration = Duration.ofMillis(400)
        val SEARCH_WAIT: Duration = Duration.ofMillis(1_500)
        val CHAT_PAGE_WAIT: Duration = Duration.ofMillis(900)
        val CALL_MENU_WAIT: Duration = Duration.ofMillis(700)
        val PROFILE_READ_INTERVAL: Duration = Duration.ofMillis(250)
        val GESTURE_TIMEOUT: Duration = Duration.ofSeconds(2)
        const val PROFILE_READ_ATTEMPTS = 12
        const val CONTACT_PROFILE_CALL_ENTRY_TEXT = "音视频通话"
        const val VOICE_CALL_TEXT = "语音通话"
        const val VIDEO_CALL_TEXT = "视频通话"
    }
}

private fun WechatCalibratedCallRequest.clearLocator() {
    targetSearchLocator.fill('\u0000')
}

private fun WechatCalibratedCallRequest.outcome(
    status: WechatSemanticCallExecutionStatus,
): WechatSemanticCallExecutionOutcome =
    WechatSemanticCallExecutionOutcome(planId, action, status)
