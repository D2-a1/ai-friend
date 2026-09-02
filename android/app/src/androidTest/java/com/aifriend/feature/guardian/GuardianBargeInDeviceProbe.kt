package com.aifriend.feature.guardian

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.aifriend.core.audio.WavPcmCodec
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/** 不含会话编号、PCM 或识别正文的真机音频效果能力快照。 */
data class GuardianAudioEffectCapability(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val acousticEchoCancelerAvailable: Boolean,
    val acousticEchoCancelerEnabled: Boolean,
    val noiseSuppressorAvailable: Boolean,
    val noiseSuppressorEnabled: Boolean,
)

/** 真机抢话探针的最小结果；原始音频不会离开探针。 */
data class GuardianBargeInDeviceReport(
    val capability: GuardianAudioEffectCapability,
    val diagnostic: GuardianBargeInDiagnosticResult,
)

/**
 * 仅供 AndroidTest 使用的纯内存 AEC/降噪与抢话诊断探针。
 *
 * 探针复用生产守护的 16 kHz、单声道、VOICE_RECOGNITION 参数，但不接入 Hilt、服务、
 * 上传或日志。测试驱动必须调用 [DeviceSession.acknowledgementStarted]、播放与生产相同的
 * [OfflineGuardianAcknowledgement]，再调用 [DeviceSession.acknowledgementFinished]。
 * 人工抢话场景还要在测试员实际开口时调用 [DeviceSession.markExternalSpeechStarted]。
 */
class GuardianBargeInDeviceProbe(private val context: Context) {

    /** 打开一次独立诊断；调用方必须在 finally 中关闭返回的会话。 */
    fun start(scenario: GuardianBargeInScenario): DeviceSession {
        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        ) { "真机抢话测试前必须显式授予麦克风权限" }
        val minimumBytes = AudioRecord.getMinBufferSize(
            WavPcmCodec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimumBytes > 0) { "设备不支持守护录音参数" }
        val sampleCount = maxOf(minimumBytes / BYTES_PER_SAMPLE, MIN_CHUNK_SAMPLES)
        val recorder = AudioRecord.Builder()
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
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "设备无法初始化守护录音"
        }
        val effects = DeviceEffects.attach(recorder.audioSessionId)
        val evaluator = GuardianBargeInEvaluator()
        return try {
            recorder.startRecording()
            DeviceSession(
                recorder = recorder,
                effects = effects,
                evaluator = evaluator,
                scenario = scenario,
                sampleCount = sampleCount,
            ).also(DeviceSession::beginCapture)
        } catch (error: Exception) {
            effects.release()
            recorder.release()
            throw error
        }
    }

    /** 单次设备诊断会话；所有 PCM 只驻留读取线程的固定 [ShortArray]。 */
    class DeviceSession internal constructor(
        private val recorder: AudioRecord,
        private val effects: DeviceEffects,
        private val evaluator: GuardianBargeInEvaluator,
        private val scenario: GuardianBargeInScenario,
        private val sampleCount: Int,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val running = AtomicBoolean(false)
        private var captureJob: Job? = null
        private var startedAtMs: Long? = null

        /** 在播放固定离线应答前记录开始时钟。 */
        fun acknowledgementStarted() {
            check(startedAtMs == null) { "本轮播报已经开始" }
            val now = SystemClock.elapsedRealtime()
            startedAtMs = now
            evaluator.start(scenario, now)
        }

        /** 在 TTS onDone/onError 返回后记录结束时钟。 */
        fun acknowledgementFinished() {
            checkNotNull(startedAtMs) { "尚未记录播报开始" }
            evaluator.acknowledgementFinished(SystemClock.elapsedRealtime())
        }

        /** 由人工测试驱动在测试员实际开口时记录一次外部标记。 */
        fun markExternalSpeechStarted() {
            checkNotNull(startedAtMs) { "尚未记录播报开始" }
            evaluator.markExternalSpeechStarted(SystemClock.elapsedRealtime())
        }

        /**
         * 停止读取、覆盖固定缓冲并释放系统效果；只返回去敏能力和时序结果。
         */
        suspend fun finish(): GuardianBargeInDeviceReport {
            running.set(false)
            runCatching { recorder.stop() }
            captureJob?.cancelAndJoin()
            val diagnostic = evaluator.finish(SystemClock.elapsedRealtime())
            effects.release()
            recorder.release()
            return GuardianBargeInDeviceReport(effects.capability, diagnostic)
        }

        /** 异常、取消、锁屏或占麦时清除诊断，且不产生可误解的通过结果。 */
        suspend fun abort() {
            running.set(false)
            runCatching { recorder.stop() }
            captureJob?.cancelAndJoin()
            evaluator.clear()
            effects.release()
            recorder.release()
        }

        internal fun beginCapture() {
            check(running.compareAndSet(false, true)) { "探针已经启动" }
            captureJob = scope.launch {
                val buffer = ShortArray(sampleCount)
                try {
                    while (running.get()) {
                        val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (read > 0 && startedAtMs != null) {
                            evaluator.observe(
                                atMs = SystemClock.elapsedRealtime(),
                                voiced = rms(buffer, read) >= MINIMUM_RMS,
                            )
                        } else if (read < 0 && running.get()) {
                            evaluator.clear()
                            running.set(false)
                        }
                    }
                } finally {
                    buffer.fill(0)
                }
            }
        }

        private fun rms(buffer: ShortArray, count: Int): Double {
            var sum = 0.0
            repeat(count) { index ->
                val value = buffer[index].toDouble()
                sum += value * value
            }
            return sqrt(sum / count)
        }
    }

    internal class DeviceEffects private constructor(
        private val acousticEchoCanceler: AcousticEchoCanceler?,
        private val noiseSuppressor: NoiseSuppressor?,
        val capability: GuardianAudioEffectCapability,
    ) {
        fun release() {
            acousticEchoCanceler?.release()
            noiseSuppressor?.release()
        }

        companion object {
            fun attach(audioSessionId: Int): DeviceEffects {
                val aecAvailable = AcousticEchoCanceler.isAvailable()
                val aec = if (aecAvailable) {
                    runCatching { AcousticEchoCanceler.create(audioSessionId) }.getOrNull()
                } else {
                    null
                }
                val aecEnabled = aec?.let { effect ->
                    runCatching {
                        effect.enabled = true
                        effect.enabled
                    }.getOrDefault(false)
                } ?: false
                val noiseSuppressorAvailable = NoiseSuppressor.isAvailable()
                val noiseSuppressor = if (noiseSuppressorAvailable) {
                    runCatching { NoiseSuppressor.create(audioSessionId) }.getOrNull()
                } else {
                    null
                }
                val noiseSuppressorEnabled = noiseSuppressor?.let { effect ->
                    runCatching {
                        effect.enabled = true
                        effect.enabled
                    }.getOrDefault(false)
                } ?: false
                return DeviceEffects(
                    acousticEchoCanceler = aec,
                    noiseSuppressor = noiseSuppressor,
                    capability = GuardianAudioEffectCapability(
                        manufacturer = Build.MANUFACTURER.orEmpty(),
                        model = Build.MODEL.orEmpty(),
                        sdkInt = Build.VERSION.SDK_INT,
                        acousticEchoCancelerAvailable = aecAvailable,
                        acousticEchoCancelerEnabled = aecEnabled,
                        noiseSuppressorAvailable = noiseSuppressorAvailable,
                        noiseSuppressorEnabled = noiseSuppressorEnabled,
                    ),
                )
            }
        }
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val MIN_CHUNK_SAMPLES = 1_600
        const val MINIMUM_RMS = 220.0
    }
}
