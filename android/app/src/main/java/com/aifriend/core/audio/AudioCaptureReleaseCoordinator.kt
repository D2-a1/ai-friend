package com.aifriend.core.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async

/**
 * 页面退出时立即进入录音清理；清理不能随页面作用域销毁而取消。
 * 只负责释放资源，不允许创建录音、提交任务或恢复执行。
 * 同一页面从主线程调用，重复退出共享尚未完成的清理。
 */
class AudioCaptureReleaseCoordinator(
    private val capture: AudioCapturePort,
    private val ownerScope: CoroutineScope,
) {
    private var pending: Deferred<Unit>? = null

    fun release(): Deferred<Unit> {
        pending?.takeUnless { it.isCompleted }?.let { return it }
        return ownerScope.async(NonCancellable, start = CoroutineStart.UNDISPATCHED) {
            capture.cancel()
        }.also { pending = it }
    }
}
