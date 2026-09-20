package com.aifriend.feature.guardian

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 等待守护服务真正释放共享的麦克风等系统资源后，再由可见任务页接管。
 *
 * [stopRequested] 为 false 表示系统中没有可停止的服务，不需要等待。服务仍在运行时
 * 只接受 [GuardianStatus.active] 变为 false 的明确完成信号；超时必须失败关闭。
 */
internal suspend fun awaitGuardianResourceRelease(
    status: Flow<GuardianStatus>,
    stopRequested: Boolean,
    timeoutMillis: Long = GUARDIAN_RESOURCE_RELEASE_TIMEOUT_MILLIS,
): Boolean {
    require(timeoutMillis > 0L) { "守护资源交接等待时间必须大于零" }
    if (!stopRequested) return true
    return withTimeoutOrNull(timeoutMillis) {
        status.first { current -> !current.active }
        true
    } ?: false
}

/**
 * 等待守护服务停止当前录音并进入前台任务交接态。
 *
 * 服务仍存活，但不持有麦克风；如果服务中途关闭或进入错误，交接失败。
 */
internal suspend fun awaitGuardianTaskHandoff(
    status: Flow<GuardianStatus>,
    timeoutMillis: Long = GUARDIAN_RESOURCE_RELEASE_TIMEOUT_MILLIS,
): Boolean {
    require(timeoutMillis > 0L) { "守护任务交接等待时间必须大于零" }
    return withTimeoutOrNull(timeoutMillis) {
        status.first { current ->
            current.mode == GuardianMode.TASK_HANDOFF || !current.active
        }.mode == GuardianMode.TASK_HANDOFF
    } ?: false
}

internal const val GUARDIAN_RESOURCE_RELEASE_TIMEOUT_MILLIS = 5_000L
