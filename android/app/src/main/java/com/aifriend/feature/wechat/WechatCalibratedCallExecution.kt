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

    suspend fun tap(point: WechatCalibrationPixelPoint): Boolean

    suspend fun longPress(point: WechatCalibrationPixelPoint): Boolean

    fun setSensitiveSearchClipboard(value: CharArray): Boolean

    fun clearSearchClipboard()

    suspend fun waitForUi(duration: Duration)

    suspend fun releaseAudioBeforeCall(): Boolean
}

class WechatCalibratedCallRequest internal constructor(
    internal val token: Long,
    val planId: String,
    val action: WechatActionType,
    val wechatVersion: String,
    val profile: WechatCalibrationProfile,
    internal val targetSearchLocator: CharArray,
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
        now: OffsetDateTime,
    ): Boolean {
        if (state != null || !plan.action.isCallAction() ||
            plan.planId.isBlank() || plan.targetSearchLocator.isBlank() ||
            profile.key.wechatVersion != wechatVersion || !plan.expiresAt.isAfter(now)
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
) {
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
            request.targetSearchLocator.fill('\u0000')
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
        tap(WechatCalibrationTarget.GLOBAL_SEARCH_INPUT, INPUT_WAIT)?.let { return it }
        validateRuntime(request, uiPort)?.let { return it }
        if (!uiPort.setSensitiveSearchClipboard(request.targetSearchLocator)) {
            return WechatSemanticCallExecutionStatus.CALIBRATED_CLIPBOARD_UNAVAILABLE
        }
        val inputPoint = request.profile.points.getValue(
            WechatCalibrationTarget.GLOBAL_SEARCH_INPUT,
        ).toPixels(
            request.profile.key.displayWidthPixels,
            request.profile.key.displayHeightPixels,
        )
        val longPressAccepted = withTimeoutOrNull(GESTURE_TIMEOUT.toMillis()) {
            uiPort.longPress(inputPoint)
        } ?: false
        if (!longPressAccepted) {
            return WechatSemanticCallExecutionStatus.CALIBRATED_GESTURE_REJECTED
        }
        uiPort.waitForUi(PASTE_MENU_WAIT)
        tap(WechatCalibrationTarget.GLOBAL_SEARCH_PASTE, SEARCH_WAIT)?.let { return it }
        uiPort.clearSearchClipboard()
        tap(WechatCalibrationTarget.SEARCH_RESULT, PAGE_WAIT)?.let { return it }
        tap(WechatCalibrationTarget.CONTACT_PROFILE_CALL_ENTRY, CALL_MENU_WAIT)?.let { return it }
        validateRuntime(request, uiPort)?.let { return it }
        if (!uiPort.releaseAudioBeforeCall()) {
            return WechatSemanticCallExecutionStatus.AUDIO_RELEASE_FAILED
        }
        val target = when (request.action) {
            WechatActionType.START_VOICE_CALL -> WechatCalibrationTarget.CALL_CHOICE_VOICE
            WechatActionType.START_VIDEO_CALL -> WechatCalibrationTarget.CALL_CHOICE_VIDEO
            WechatActionType.SEND_AUDIO_AND_TEXT ->
                return WechatSemanticCallExecutionStatus.CALIBRATED_EXECUTION_INTERRUPTED
        }
        return tap(target, Duration.ZERO)
            ?: WechatSemanticCallExecutionStatus.HANDED_TO_WECHAT
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
        val PASTE_MENU_WAIT: Duration = Duration.ofMillis(400)
        val SEARCH_WAIT: Duration = Duration.ofMillis(1_000)
        val CALL_MENU_WAIT: Duration = Duration.ofMillis(600)
        val GESTURE_TIMEOUT: Duration = Duration.ofSeconds(2)
    }
}

private fun WechatCalibratedCallRequest.clearLocator() {
    targetSearchLocator.fill('\u0000')
}

private fun WechatCalibratedCallRequest.outcome(
    status: WechatSemanticCallExecutionStatus,
): WechatSemanticCallExecutionOutcome =
    WechatSemanticCallExecutionOutcome(planId, action, status)
