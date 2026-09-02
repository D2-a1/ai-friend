package com.aifriend.feature.guardian

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 微信通话最终点击前与守护服务之间的进程内麦克风交接。
 *
 * 不保存音频、联系人或动作内容；守护服务未运行时视为没有麦克风需要释放。
 */
@Singleton
class GuardianWechatCallAudioCoordinator @Inject constructor() {
    private var handler: Handler? = null

    @Synchronized
    fun register(
        owner: Any,
        releaseBeforeCall: suspend () -> Unit,
        resumeIfIdle: suspend () -> Unit,
    ) {
        handler = Handler(owner, releaseBeforeCall, resumeIfIdle)
    }

    @Synchronized
    fun unregister(owner: Any) {
        if (handler?.owner === owner) handler = null
    }

    suspend fun releaseBeforeCall(): Boolean {
        val current = synchronized(this) { handler } ?: return true
        return withTimeoutOrNull(OPERATION_TIMEOUT_MILLIS) {
            runCatching {
                current.releaseBeforeCall()
                true
            }.getOrDefault(false)
        } ?: false
    }

    suspend fun resumeIfIdle() {
        val current = synchronized(this) { handler } ?: return
        withTimeoutOrNull(OPERATION_TIMEOUT_MILLIS) {
            runCatching { current.resumeIfIdle() }
        }
    }

    private data class Handler(
        val owner: Any,
        val releaseBeforeCall: suspend () -> Unit,
        val resumeIfIdle: suspend () -> Unit,
    )

    private companion object {
        const val OPERATION_TIMEOUT_MILLIS = 1_500L
    }
}
