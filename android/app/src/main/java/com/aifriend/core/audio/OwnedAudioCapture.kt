package com.aifriend.core.audio

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 令牌仅为进程内对象身份，不持久化；驱动必须在同一会话锁内核对身份后操作硬件。 */
internal interface OwnedAudioCaptureDriver {
    suspend fun startOwned(owner: Any, maxDurationMs: Int, automaticEndpoint: Boolean)
    suspend fun awaitOwned(owner: Any): SpeechEndpointBoundary
    suspend fun stopOwned(owner: Any): CapturedAudio
    suspend fun cancelOwned(owner: Any)
}

/** 每个问答页面创建新租约。cancel永久关闭本租约，禁止旧回调再次启动；不代表关闭其他录音。 */
internal class OwnedAudioCapture(private val driver: OwnedAudioCaptureDriver) : AudioCapturePort {
    private val owner = Any()
    private val closed = AtomicBoolean(false)
    private val operation = Mutex()
    private val mutableState = MutableStateFlow(AudioCaptureState.STOPPED)
    override val state = mutableState.asStateFlow()
    override suspend fun start(maxDurationMs: Int) = start(maxDurationMs, false)
    override suspend fun startUtterance(maxDurationMs: Int) = start(maxDurationMs, true)

    private suspend fun start(duration: Int, endpoint: Boolean) = operation.withLock {
        currentCoroutineContext().ensureActive()
        check(!closed.get()) { "AUDIO_LEASE_CLOSED" }
        mutableState.value = AudioCaptureState.STARTING
        try {
            // 先完成硬件启动/失败清理，再响应取消，避免withContext返回时丢失已启动录音的归属。
            withContext(NonCancellable) { driver.startOwned(owner, duration, endpoint) }
            currentCoroutineContext().ensureActive()
            if (closed.get()) throw CancellationException("AUDIO_LEASE_CLOSED")
            mutableState.value = AudioCaptureState.CAPTURING
        } catch (failure: Throwable) {
            closed.set(true)
            withContext(NonCancellable) {
                try { driver.cancelOwned(owner) } catch (_: Exception) { /* 保留启动失败 */ }
            }
            mutableState.value = AudioCaptureState.FAILED
            throw failure
        }
    }

    override suspend fun awaitSpeechEndpoint(): SpeechEndpointBoundary {
        currentCoroutineContext().ensureActive()
        if (closed.get()) return SpeechEndpointBoundary.STOPPED
        val boundary = driver.awaitOwned(owner)
        currentCoroutineContext().ensureActive()
        return if (closed.get()) SpeechEndpointBoundary.STOPPED else boundary
    }

    override suspend fun stop(): CapturedAudio = operation.withLock {
        currentCoroutineContext().ensureActive()
        check(!closed.get()) { "AUDIO_LEASE_CLOSED" }
        var audio: CapturedAudio? = null
        var delivered = false
        mutableState.value = AudioCaptureState.STOPPING
        try {
            withContext(NonCancellable) { audio = driver.stopOwned(owner) }
            currentCoroutineContext().ensureActive()
            if (closed.get()) throw CancellationException("AUDIO_LEASE_CLOSED")
            mutableState.value = AudioCaptureState.STOPPED
            audio!!.also { delivered = true }
        } finally {
            if (!delivered) {
                audio?.clear(); closed.set(true)
                withContext(NonCancellable) { driver.cancelOwned(owner) }
                mutableState.value = AudioCaptureState.FAILED
            }
        }
    }

    override suspend fun cancel() {
        closed.set(true) // 不等待正在启动的协程，立即关闭本机启动门闩。
        withContext(NonCancellable) {
            operation.withLock {
                try { driver.cancelOwned(owner); mutableState.value = AudioCaptureState.STOPPED }
                catch (failure: Exception) { mutableState.value = AudioCaptureState.FAILED; throw failure }
            }
        }
    }
}

/** null为既有普通采集调用；非null为某一新租约。绝不以equals或可复制编号比较。 */
internal fun ownsAudioSession(expected: Any?, actual: Any?): Boolean = expected === actual

/** 页面取消不要求焦点代次；系统回调必须匹配它创建时的唯一请求。 */
internal fun matchesAudioFocusRequest(expected: Any?, actual: Any?): Boolean = expected == null || expected === actual
