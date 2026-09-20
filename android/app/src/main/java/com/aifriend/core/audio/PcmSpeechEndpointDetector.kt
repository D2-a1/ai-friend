package com.aifriend.core.audio

import kotlin.math.sqrt

/**
 * 16 位小端、单声道 PCM 的保守语音端点检测器。
 *
 * 能量只用于判断“还没开口”和“说完后的连续静音”，不会参与文本、意图、联系人或
 * 说话人识别。正常停顿不会立即结束；即使自动检测受环境噪声影响，页面仍保留明确的
 * 手动结束入口。
 */
class PcmSpeechEndpointDetector(
    private val sampleRate: Int = WavPcmCodec.SAMPLE_RATE,
    maximumDurationMs: Int,
    private val initialSilenceMs: Int = INITIAL_SILENCE_MS,
    private val trailingSilenceMs: Int = TRAILING_SILENCE_MS,
    private val minimumSpeechMs: Int = MINIMUM_SPEECH_MS,
    private val minimumRms: Double = MINIMUM_RMS,
) {
    private val maximumSamples = millisecondsToSamples(maximumDurationMs)
    private var totalSamples = 0L
    private var speechSamples = 0L
    private var trailingSilenceSamples = 0L
    private var terminalBoundary: SpeechEndpointBoundary? = null

    init {
        require(sampleRate > 0 && maximumSamples > 0) { "录音端点容量无效" }
        require(initialSilenceMs > 0 && trailingSilenceMs > 0 && minimumSpeechMs > 0) {
            "录音端点时间边界无效"
        }
        require(minimumRms.isFinite() && minimumRms > 0.0) { "录音端点能量边界无效" }
    }

    /** 追加一段 PCM；终态一旦产生便保持不变。 */
    @Synchronized
    fun appendPcm16Le(
        bytes: ByteArray,
        offset: Int = 0,
        count: Int = bytes.size - offset,
    ): SpeechEndpointBoundary {
        terminalBoundary?.let { return it }
        require(offset >= 0 && count >= 0 && offset + count <= bytes.size) {
            "PCM 数据范围无效"
        }
        val sampleCount = count / Short.SIZE_BYTES
        if (sampleCount <= 0) return SpeechEndpointBoundary.CONTINUE

        var squaredSum = 0.0
        var byteIndex = offset
        repeat(sampleCount) {
            val low = bytes[byteIndex].toInt() and 0xFF
            val high = bytes[byteIndex + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toDouble()
            squaredSum += sample * sample
            byteIndex += Short.SIZE_BYTES
        }
        totalSamples += sampleCount
        val voiced = sqrt(squaredSum / sampleCount) >= minimumRms
        if (voiced) {
            speechSamples += sampleCount
            trailingSilenceSamples = 0
        } else if (speechSamples > 0) {
            trailingSilenceSamples += sampleCount
        }

        val boundary = when {
            totalSamples >= maximumSamples -> SpeechEndpointBoundary.MAXIMUM_REACHED
            speechSamples == 0L &&
                totalSamples >= millisecondsToSamples(initialSilenceMs) ->
                SpeechEndpointBoundary.NO_SPEECH_TIMEOUT
            speechSamples >= millisecondsToSamples(minimumSpeechMs) &&
                trailingSilenceSamples >= millisecondsToSamples(trailingSilenceMs) ->
                SpeechEndpointBoundary.UTTERANCE_COMPLETE
            else -> SpeechEndpointBoundary.CONTINUE
        }
        if (boundary != SpeechEndpointBoundary.CONTINUE) terminalBoundary = boundary
        return boundary
    }

    private fun millisecondsToSamples(milliseconds: Int): Long =
        sampleRate.toLong() * milliseconds / 1_000L

    private companion object {
        const val INITIAL_SILENCE_MS = 5_000
        const val TRAILING_SILENCE_MS = 3_000
        const val MINIMUM_SPEECH_MS = 300
        const val MINIMUM_RMS = 220.0
    }
}
