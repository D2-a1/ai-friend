package com.aifriend.feature.guardian

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 问答与守护的独立音频互斥；无任务内容、微信动作令牌、持久启用设置或服务启动权限。 */
@Singleton
class GuardianQuestionAudioCoordinator @Inject constructor() {
    private class Handler(val owner: Any, val pause: suspend () -> Boolean, val resume: () -> Unit)
    private val mutex = Mutex()
    private enum class Reservation { FREE, HELD, CLEANUP_FAILED }
    private val reserved = MutableStateFlow(Reservation.FREE)
    @Volatile private var handler: Handler? = null
    private var questionOwner: Any? = null

    @Synchronized fun register(owner: Any, pause: suspend () -> Boolean, resume: () -> Unit) {
        handler = Handler(owner, pause, resume)
    }
    @Synchronized fun unregister(owner: Any) { if (handler?.owner === owner) handler = null }

    interface Lease {
        suspend fun release()
        suspend fun retainAfterCleanupFailure()
    }

    suspend fun acquireQuestion(): Lease? {
        var lease: Lease? = null
        var delivered = false
        try {
            val acquired = withTimeoutOrNull(5_000) {
                mutex.withLock {
                    if (questionOwner != null) return@withLock false
                    val token = Any(); val captured = handler
                    questionOwner = token; reserved.value = Reservation.HELD
                    var ready = false
                    try {
                        if (captured != null && !captured.pause()) return@withLock false
                        currentCoroutineContext().ensureActive()
                        lease = object : Lease {
                            override suspend fun release() = withContext(NonCancellable) {
                                mutex.withLock {
                                    if (questionOwner !== token || reserved.value == Reservation.CLEANUP_FAILED) return@withLock
                                    // 只恢复原服务实例。新服务自己的启动请求等待reservation释放。
                                    questionOwner = null; reserved.value = Reservation.FREE
                                    if (handler === captured) captured?.resume?.invoke()
                                }
                            }
                            override suspend fun retainAfterCleanupFailure() = withContext(NonCancellable) {
                                mutex.withLock {
                                    if (questionOwner === token) reserved.value = Reservation.CLEANUP_FAILED
                                }
                            }
                        }
                        ready = true
                        true
                    } finally {
                        if (!ready) {
                            questionOwner = null; reserved.value = Reservation.FREE
                            if (handler === captured) captured?.resume?.invoke()
                        }
                    }
                }
            } == true
            currentCoroutineContext().ensureActive()
            if (!acquired) return null
            delivered = true
            return lease
        } finally { if (!delivered) withContext(NonCancellable) { lease?.release() } }
    }

    /** 所有守护开麦/模型准备入口必须在此锁中；显式启动可等待，后台恢复只尝试一次。 */
    suspend fun guardianOperation(owner: Any, wait: Boolean = false, block: suspend () -> Unit): Boolean {
        while (true) {
            if (wait) reserved.first { it != Reservation.HELD }
            var retry = false
            val ran = mutex.withLock {
                currentCoroutineContext().ensureActive()
                if (handler?.owner !== owner) return@withLock false
                if (reserved.value == Reservation.CLEANUP_FAILED) return@withLock false
                if (questionOwner != null) { retry = wait; return@withLock false }
                block()
                true
            }
            if (!retry) return ran
        }
    }
}
