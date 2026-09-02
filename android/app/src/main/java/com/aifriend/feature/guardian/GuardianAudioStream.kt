package com.aifriend.feature.guardian

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.aifriend.core.audio.WavPcmCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 守护休眠阶段的纯内存 PCM 流。 */
interface GuardianAudioStream {
    suspend fun start(
        onChunk: (ShortArray, Int, Long) -> Unit,
        onFailure: (GuardianAudioFailure) -> Unit,
    )

    suspend fun stop()
}

/** 不携带原始音频、路径或系统异常正文的流式录音失败类型。 */
enum class GuardianAudioFailure(val userMessage: String) {
    PERMISSION_DENIED("麦克风权限已关闭，小友守护已停止"),
    DEVICE_UNAVAILABLE("当前设备无法使用麦克风，小友守护已停止"),
    CAPTURE_INTERRUPTED("麦克风录音被中断，小友守护已停止"),
}

/**
 * 使用 [AudioRecord] 直接向固定内存缓冲提供 PCM 的守护录音实现。
 *
 * 不创建文件、不返回 WAV、不记录音频内容；读取缓冲在每次会话结束时覆盖。
 *
 * @author codex
 * @since 2026-08-14
 */
@Singleton
class AndroidGuardianAudioStream @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : GuardianAudioStream {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var session: StreamSession? = null

    override suspend fun start(
        onChunk: (ShortArray, Int, Long) -> Unit,
        onFailure: (GuardianAudioFailure) -> Unit,
    ) = mutex.withLock {
        if (session != null) return@withLock
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw GuardianAudioStreamException(GuardianAudioFailure.PERMISSION_DENIED)
        }
        val minimumBytes = AudioRecord.getMinBufferSize(
            WavPcmCodec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBytes <= 0) {
            throw GuardianAudioStreamException(GuardianAudioFailure.DEVICE_UNAVAILABLE)
        }
        val sampleCount = maxOf(minimumBytes / BYTES_PER_SAMPLE, MIN_CHUNK_SAMPLES)
        val recorder = runCatching {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(WavPcmCodec.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(sampleCount * BYTES_PER_SAMPLE * 2)
                .build()
        }.getOrElse {
            throw GuardianAudioStreamException(GuardianAudioFailure.DEVICE_UNAVAILABLE)
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw GuardianAudioStreamException(GuardianAudioFailure.DEVICE_UNAVAILABLE)
        }
        runCatching { recorder.startRecording() }.getOrElse {
            recorder.release()
            throw GuardianAudioStreamException(GuardianAudioFailure.DEVICE_UNAVAILABLE)
        }
        val current = StreamSession(recorder, AtomicBoolean(true))
        session = current
        current.job = scope.launch {
            capture(current, sampleCount, onChunk, onFailure)
        }
    }

    override suspend fun stop() {
        val current = mutex.withLock {
            session?.also { session = null }
        } ?: return
        current.running.set(false)
        runCatching { current.recorder.stop() }
        current.job?.cancelAndJoin()
        runCatching { current.recorder.release() }
    }

    private fun capture(
        current: StreamSession,
        sampleCount: Int,
        onChunk: (ShortArray, Int, Long) -> Unit,
        onFailure: (GuardianAudioFailure) -> Unit,
    ) {
        val buffer = ShortArray(sampleCount)
        try {
            while (current.running.get()) {
                val read = current.recorder.read(
                    buffer,
                    0,
                    buffer.size,
                    AudioRecord.READ_BLOCKING,
                )
                when {
                    read > 0 -> onChunk(buffer, read, SystemClock.elapsedRealtime())
                    read == 0 -> Unit
                    current.running.get() -> {
                        current.running.set(false)
                        onFailure(GuardianAudioFailure.CAPTURE_INTERRUPTED)
                    }
                }
            }
        } catch (_: Exception) {
            if (current.running.getAndSet(false)) {
                onFailure(GuardianAudioFailure.CAPTURE_INTERRUPTED)
            }
        } finally {
            buffer.fill(0)
            runCatching { current.recorder.stop() }
            runCatching { current.recorder.release() }
        }
    }

    private class StreamSession(
        val recorder: AudioRecord,
        val running: AtomicBoolean,
        var job: Job? = null,
    )

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val MIN_CHUNK_SAMPLES = 1_600
    }
}

/** 流式录音启动失败。 */
class GuardianAudioStreamException(
    val failure: GuardianAudioFailure,
) : IllegalStateException(failure.userMessage)
