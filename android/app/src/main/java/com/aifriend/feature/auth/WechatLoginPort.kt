package com.aifriend.feature.auth

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 微信一次性授权启动结果。 */
sealed interface WechatLoginLaunchResult {
    data object Started : WechatLoginLaunchResult

    data class Unavailable(val message: String) : WechatLoginLaunchResult
}

/** 微信一次性授权回调；授权码只允许立即交给后端兑换。 */
sealed interface WechatLoginEvent {
    class Authorized(val code: String) : WechatLoginEvent {
        override fun toString(): String = "WechatLoginEvent.Authorized(code=<redacted>)"
    }

    data class Failed(val message: String) : WechatLoginEvent
}

/** 微信 SDK 回调的有限结果，不向业务层暴露第三方原始响应。 */
enum class WechatLoginCallbackOutcome {
    AUTHORIZED,
    CANCELLED,
    DENIED,
    FAILED,
}

/** 启动微信授权并提供当前进程一次性结果。 */
interface WechatLoginPort {
    val events: Flow<WechatLoginEvent>

    fun launch(): WechatLoginLaunchResult

    fun clear()
}

/**
 * 当前进程微信授权 state 门闩。
 *
 * 不写 Room 或 DataStore；进程重建、state 不匹配、取消或任一终态都会失败关闭。
 */
@Singleton
class WechatLoginCallbackBroker @Inject constructor() {
    private val mutableEvents = MutableSharedFlow<WechatLoginEvent>(extraBufferCapacity = 1)
    private var pendingState: String? = null

    val events: Flow<WechatLoginEvent> = mutableEvents.asSharedFlow()

    @Synchronized
    fun begin(state: String): Boolean {
        if (!state.matches(STATE_PATTERN) || pendingState != null) return false
        pendingState = state
        return true
    }

    @Synchronized
    fun cancel(state: String) {
        if (constantTimeEquals(pendingState, state)) pendingState = null
    }

    @Synchronized
    fun complete(
        state: String?,
        code: String?,
        outcome: WechatLoginCallbackOutcome,
    ) {
        val expectedState = pendingState ?: return
        pendingState = null
        val event = when {
            !constantTimeEquals(expectedState, state) -> WechatLoginEvent.Failed(
                "微信登录校验失败，请重新登录",
            )
            outcome == WechatLoginCallbackOutcome.AUTHORIZED &&
                code != null && code.isNotBlank() && code.length <= MAXIMUM_CODE_LENGTH ->
                WechatLoginEvent.Authorized(code)
            outcome == WechatLoginCallbackOutcome.CANCELLED -> WechatLoginEvent.Failed(
                "您已取消微信登录",
            )
            outcome == WechatLoginCallbackOutcome.DENIED -> WechatLoginEvent.Failed(
                "微信没有允许本次登录，请重新确认",
            )
            else -> WechatLoginEvent.Failed("微信登录没有完成，请稍后重试")
        }
        mutableEvents.tryEmit(event)
    }

    @Synchronized
    fun clear() {
        pendingState = null
    }

    private fun constantTimeEquals(left: String?, right: String?): Boolean {
        if (left == null || right == null) return false
        return MessageDigest.isEqual(left.encodeToByteArray(), right.encodeToByteArray())
    }

    private companion object {
        val STATE_PATTERN = Regex("^[0-9a-f]{32}$")
        const val MAXIMUM_CODE_LENGTH = 512
    }
}
