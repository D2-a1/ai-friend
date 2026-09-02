package com.aifriend.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 使用 [AudioTrack] 回放应用内部 PCM WAV 的适配器。
 *
 * @author codex
 * @since 2026-08-12
 */
@Singleton
class AndroidAudioPlaybackAdapter @Inject constructor(
    @ApplicationContext context: Context,
) : AudioPlaybackPort {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(AudioPlaybackState.STOPPED)
    private val playing = AtomicBoolean(false)
    private val monitor = Any()
    private var activeTrack: AudioTrack? = null
    private var activeFocusRequest: AudioFocusRequest? = null

    override val state: StateFlow<AudioPlaybackState> = mutableState.asStateFlow()

    override suspend fun play(wavBytes: ByteArray) {
        val pcm = runCatching { WavPcmCodec.decodeMono16(wavBytes) }
            .getOrElse { exception ->
                mutableState.value = AudioPlaybackState.FAILED
                throw AudioPlaybackException("录音无法回放", exception)
            }
        PlaybackSampleGainNormalizer.applyInPlace(pcm.samples)
        synchronized(monitor) {
            check(activeTrack == null) { "当前已在回放录音" }
            mutableState.value = AudioPlaybackState.STARTING
            requestAudioFocus()
            try {
                val minimumBufferSize = AudioTrack.getMinBufferSize(
                    pcm.sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minimumBufferSize <= 0) {
                    throw AudioPlaybackException("当前设备无法回放录音")
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(pcm.sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(minimumBufferSize, MIN_PLAYBACK_BUFFER_BYTES))
                    .build()
                activeTrack = track
                playing.set(true)
                track.play()
                mutableState.value = AudioPlaybackState.PLAYING
            } catch (exception: Exception) {
                playing.set(false)
                runCatching { activeTrack?.release() }
                activeTrack = null
                abandonAudioFocus()
                mutableState.value = AudioPlaybackState.FAILED
                if (exception is AudioPlaybackException) throw exception
                throw AudioPlaybackException("当前设备无法回放录音", exception)
            }
        }
        try {
            withContext(Dispatchers.IO) {
                var offset = 0
                while (playing.get() && offset < pcm.samples.size) {
                    val written = activeTrack?.write(
                        pcm.samples,
                        offset,
                        pcm.samples.size - offset,
                        AudioTrack.WRITE_BLOCKING,
                    ) ?: break
                    if (written < 0) throw AudioPlaybackException("录音回放被中断")
                    offset += written
                }
            }
        } finally {
            synchronized(monitor) {
                playing.set(false)
                runCatching { activeTrack?.stop() }
                runCatching { activeTrack?.flush() }
                runCatching { activeTrack?.release() }
                activeTrack = null
                abandonAudioFocus()
                mutableState.value = AudioPlaybackState.STOPPED
            }
            pcm.samples.fill(0)
        }
    }

    override suspend fun stop() = stopImmediately()

    override fun stopImmediately() {
        synchronized(monitor) {
            if (activeTrack == null) {
                mutableState.value = AudioPlaybackState.STOPPED
                return
            }
            mutableState.value = AudioPlaybackState.STOPPING
            playing.set(false)
            runCatching { activeTrack?.pause() }
            runCatching { activeTrack?.flush() }
            runCatching { activeTrack?.stop() }
        }
    }

    private fun requestAudioFocus() {
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { focusChange ->
                if (focusChange < 0) {
                    playbackScope.launch { stop() }
                }
            }
            .build()
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mutableState.value = AudioPlaybackState.FAILED
            throw AudioPlaybackException("音频正在被其他应用使用")
        }
        activeFocusRequest = focusRequest
    }

    private fun abandonAudioFocus() {
        activeFocusRequest?.let { request ->
            runCatching { audioManager.abandonAudioFocusRequest(request) }
        }
        activeFocusRequest = null
    }

    private companion object {
        const val MIN_PLAYBACK_BUFFER_BYTES = 3_200
    }
}

/** 只对当前内存播放副本执行有界增益，不修改原始 WAV。 */
internal object PlaybackSampleGainNormalizer {

    /**
     * 把低电平 PCM 放大到可试听范围，并把峰值限制在安全目标内。
     *
     * @param samples 当前播放副本
     * @return 实际应用的增益倍数
     */
    fun applyInPlace(samples: ShortArray): Double {
        val peak = samples.maxOfOrNull { sample -> abs(sample.toInt()) } ?: return 1.0
        if (peak == 0) return 1.0
        val gain = (TARGET_PEAK_SAMPLE / peak.toDouble())
            .coerceIn(1.0, MAX_PLAYBACK_GAIN)
        if (gain == 1.0) return gain
        samples.indices.forEach { index ->
            samples[index] = (samples[index] * gain)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return gain
    }

    private const val TARGET_PEAK_RATIO = 0.70
    private const val MAX_PLAYBACK_GAIN = 8.0
    private val TARGET_PEAK_SAMPLE = Short.MAX_VALUE * TARGET_PEAK_RATIO
}

/**
 * 录音回放失败。
 */
class AudioPlaybackException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
