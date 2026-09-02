package com.aifriend.feature.guardian

import com.aifriend.core.audio.CapturedAudio
import com.aifriend.core.audio.WavPcmCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** 唤醒后纯内存任务录音的边界结果。 */
enum class GuardianCaptureBoundary {
    CONTINUE,
    NO_SPEECH_TIMEOUT,
    UTTERANCE_COMPLETE,
    MAXIMUM_REACHED,
}

/**
 * 第二次唤醒后的固定上限 PCM 捕获器。
 *
 * 能量判断只用于结束录音，不判断文本、意图、联系人或说话人身份。全部数据仅驻留当前
 * 对象的固定 [ShortArray]，读取、取消或失败后立即覆盖清零。
 */
class GuardianTaskCapture(
    private val sampleRate: Int = WavPcmCodec.SAMPLE_RATE,
    maximumDurationMs: Int = MAXIMUM_DURATION_MS,
    private val initialSilenceMs: Int = INITIAL_SILENCE_MS,
    private val trailingSilenceMs: Int = TRAILING_SILENCE_MS,
    private val minimumSpeechMs: Int = MINIMUM_SPEECH_MS,
    private val minimumRms: Double = MINIMUM_RMS,
) {
    private val samples = ShortArray(sampleRate * maximumDurationMs / 1_000)
    private var size = 0
    private var speechSamples = 0
    private var trailingSilenceSamples = 0
    private var started = false

    init {
        require(sampleRate > 0 && samples.isNotEmpty()) { "任务录音容量无效" }
        require(initialSilenceMs > 0 && trailingSilenceMs > 0 && minimumSpeechMs > 0) {
            "任务录音时间边界无效"
        }
        require(minimumRms.isFinite() && minimumRms > 0.0) { "任务录音能量边界无效" }
    }

    @Synchronized
    fun start() {
        clear()
        started = true
    }

    @Synchronized
    fun append(chunk: ShortArray, count: Int): GuardianCaptureBoundary {
        if (!started || count <= 0 || count > chunk.size) return GuardianCaptureBoundary.CONTINUE
        val copyCount = minOf(count, samples.size - size)
        if (copyCount <= 0) return GuardianCaptureBoundary.MAXIMUM_REACHED
        chunk.copyInto(samples, destinationOffset = size, endIndex = copyCount)
        size += copyCount

        val voiced = rms(chunk, copyCount) >= minimumRms
        if (voiced) {
            speechSamples += copyCount
            trailingSilenceSamples = 0
        } else if (speechSamples > 0) {
            trailingSilenceSamples += copyCount
        }

        val minimumSpeechSamples = millisecondsToSamples(minimumSpeechMs)
        val boundary = when {
            size >= samples.size -> GuardianCaptureBoundary.MAXIMUM_REACHED
            speechSamples == 0 && size >= millisecondsToSamples(initialSilenceMs) ->
                GuardianCaptureBoundary.NO_SPEECH_TIMEOUT
            speechSamples >= minimumSpeechSamples &&
                trailingSilenceSamples >= millisecondsToSamples(trailingSilenceMs) ->
                GuardianCaptureBoundary.UTTERANCE_COMPLETE
            else -> GuardianCaptureBoundary.CONTINUE
        }
        if (boundary != GuardianCaptureBoundary.CONTINUE) started = false
        return boundary
    }

    @Synchronized
    fun finish(): CapturedAudio {
        check(size > 0 && speechSamples >= millisecondsToSamples(minimumSpeechMs)) {
            "没有录到有效任务内容"
        }
        val retainedSamples = (size - trailingSilenceSamples + millisecondsToSamples(TRAILING_KEEP_MS))
            .coerceIn(1, size)
        val pcm = ByteBuffer.allocate(retainedSamples * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        repeat(retainedSamples) { index -> pcm.putShort(samples[index]) }
        val pcmBytes = pcm.array()
        return try {
            CapturedAudio(
                wavBytes = WavPcmCodec.encodeMono16(pcmBytes),
                durationMs = retainedSamples * 1_000 / sampleRate,
            )
        } finally {
            pcmBytes.fill(0)
            clear()
        }
    }

    @Synchronized
    fun clear() {
        samples.fill(0)
        size = 0
        speechSamples = 0
        trailingSilenceSamples = 0
        started = false
    }

    @Synchronized
    fun isCapturing(): Boolean = started

    private fun rms(chunk: ShortArray, count: Int): Double {
        var sum = 0.0
        repeat(count) { index ->
            val value = chunk[index].toDouble()
            sum += value * value
        }
        return sqrt(sum / count)
    }

    private fun millisecondsToSamples(milliseconds: Int): Int = sampleRate * milliseconds / 1_000

    private companion object {
        const val MAXIMUM_DURATION_MS = 60_000
        const val INITIAL_SILENCE_MS = 5_000
        const val TRAILING_SILENCE_MS = 1_200
        const val TRAILING_KEEP_MS = 250
        const val MINIMUM_SPEECH_MS = 300
        const val MINIMUM_RMS = 220.0
    }
}
