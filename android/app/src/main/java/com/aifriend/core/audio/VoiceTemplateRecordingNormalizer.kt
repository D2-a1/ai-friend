package com.aifriend.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * 称呼与固定安全指令专用的短语音预处理器。
 *
 * 只在当前进程内裁掉首尾静音并返回新的 WAV；调用方必须立即清零原始录音，
 * 使用完标准化结果后也必须清零。该处理不转写、不判断字数或文字内容。
 */
@Singleton
class VoiceTemplateRecordingNormalizer @Inject constructor() {

    private val analyzer = AliasRecordingQualityAnalyzer.voiceTemplateCompatible()

    /**
     * 裁掉首尾静音，并按实际发音而不是按钮按下总时长检查短句。
     */
    fun normalize(audio: CapturedAudio): VoiceTemplateRecordingResult {
        val pcm = runCatching { WavPcmCodec.decodeMono16(audio.wavBytes) }
            .getOrElse {
                return VoiceTemplateRecordingResult.Rejected(
                    AliasRecordingQualityIssue.INVALID_AUDIO,
                    "录音格式无效，请重新录制",
                )
            }
        try {
            if (pcm.sampleRate != WavPcmCodec.SAMPLE_RATE ||
                pcm.durationMs !in MINIMUM_CAPTURE_DURATION_MS..MAXIMUM_CAPTURE_DURATION_MS
            ) {
                return VoiceTemplateRecordingResult.Rejected(
                    AliasRecordingQualityIssue.TOO_SHORT,
                    "录音时长无效，请完整说出当前称呼或指令",
                )
            }
            val frameSize = pcm.sampleRate * FRAME_DURATION_MS / 1_000
            val frameCount = pcm.samples.size / frameSize
            if (frameCount == 0) {
                return tooShort()
            }
            val activeFrames = ArrayList<Int>(frameCount)
            for (frameIndex in 0 until frameCount) {
                val start = frameIndex * frameSize
                var energy = 0.0
                for (sampleIndex in start until start + frameSize) {
                    val normalized = pcm.samples[sampleIndex] / Short.MAX_VALUE.toDouble()
                    energy += normalized * normalized
                }
                if (sqrt(energy / frameSize) >= ACTIVE_FRAME_ROOT_MEAN_SQUARE) {
                    activeFrames += frameIndex
                }
            }
            if (activeFrames.isEmpty()) {
                return VoiceTemplateRecordingResult.Rejected(
                    AliasRecordingQualityIssue.TOO_QUIET,
                    "没有听清，请靠近麦克风并完整说出当前称呼或指令",
                )
            }
            if (activeFrames.size * FRAME_DURATION_MS < MINIMUM_ACTIVE_SPEECH_MS) {
                return tooShort()
            }
            val paddingSamples = pcm.sampleRate * EDGE_PADDING_MS / 1_000
            val firstSample = (activeFrames.first() * frameSize - paddingSamples).coerceAtLeast(0)
            val lastSampleExclusive = (
                (activeFrames.last() + 1) * frameSize + paddingSamples
                ).coerceAtMost(pcm.samples.size)
            val trimmedSamples = pcm.samples.copyOfRange(firstSample, lastSampleExclusive)
            val pcmBytes = ByteArray(trimmedSamples.size * Short.SIZE_BYTES)
            try {
                ByteBuffer.wrap(pcmBytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                    .put(trimmedSamples)
                val normalizedAudio = CapturedAudio(
                    wavBytes = WavPcmCodec.encodeMono16(pcmBytes, pcm.sampleRate),
                    durationMs = trimmedSamples.size * 1_000 / pcm.sampleRate,
                )
                return when (val quality = analyzer.analyze(normalizedAudio)) {
                    is AliasRecordingQualityResult.Passed ->
                        VoiceTemplateRecordingResult.Passed(
                            audio = normalizedAudio,
                            durationMs = quality.durationMs,
                        )
                    is AliasRecordingQualityResult.Rejected -> {
                        normalizedAudio.clear()
                        VoiceTemplateRecordingResult.Rejected(
                            issue = quality.issue,
                            message = quality.message,
                        )
                    }
                }
            } finally {
                trimmedSamples.fill(0)
                pcmBytes.fill(0)
            }
        } finally {
            pcm.samples.fill(0)
        }
    }

    private fun tooShort() = VoiceTemplateRecordingResult.Rejected(
        AliasRecordingQualityIssue.TOO_SHORT,
        "有效发音太短，请完整说出当前称呼或指令；短词无需故意拖长",
    )

    private companion object {
        const val FRAME_DURATION_MS = 20
        const val EDGE_PADDING_MS = 80
        const val MINIMUM_ACTIVE_SPEECH_MS = 300
        const val MINIMUM_CAPTURE_DURATION_MS = 300
        const val MAXIMUM_CAPTURE_DURATION_MS = 5_000
        const val ACTIVE_FRAME_ROOT_MEAN_SQUARE = 0.006
    }
}

/** 短语音预处理结果；成功结果中的音频由调用方负责清零。 */
sealed interface VoiceTemplateRecordingResult {
    data class Passed(
        val audio: CapturedAudio,
        val durationMs: Int,
    ) : VoiceTemplateRecordingResult

    data class Rejected(
        val issue: AliasRecordingQualityIssue,
        val message: String,
    ) : VoiceTemplateRecordingResult
}
