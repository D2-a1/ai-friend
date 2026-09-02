package com.aifriend.core.audio

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 称呼录音的本地粗粒度质量预检。
 *
 * <p>该检查只用于拦截过短、过轻或严重削波的录音，不判断发音内容，
 * 不代替服务端签名方言包的一致性与唯一性校验。
 *
 * @author codex
 * @since 2026-08-12
 */
class AliasRecordingQualityAnalyzer private constructor(
    private val thresholds: QualityThresholds,
) {

    /** 使用真机正式质量阈值。 */
    constructor() : this(STANDARD_THRESHOLDS)

    /**
     * 检查一段称呼 WAV 是否达到上传前的最低质量。
     *
     * @param audio 当前录音
     * @return 质量结果
     */
    fun analyze(audio: CapturedAudio): AliasRecordingQualityResult {
        val pcm = runCatching { WavPcmCodec.decodeMono16(audio.wavBytes) }
            .getOrElse {
                return AliasRecordingQualityResult.Rejected(
                    AliasRecordingQualityIssue.INVALID_AUDIO,
                    "录音格式无效，请重新录制",
                )
            }
        if (abs(audio.durationMs - pcm.durationMs) > MAX_DURATION_DIFFERENCE_MS) {
            return AliasRecordingQualityResult.Rejected(
                AliasRecordingQualityIssue.INVALID_AUDIO,
                "录音时长无法确认，请重新录制",
            )
        }
        if (pcm.durationMs < thresholds.minimumDurationMs) {
            return AliasRecordingQualityResult.Rejected(
                AliasRecordingQualityIssue.TOO_SHORT,
                thresholds.tooShortMessage,
            )
        }
        if (pcm.durationMs > MAX_DURATION_MS) {
            return AliasRecordingQualityResult.Rejected(
                AliasRecordingQualityIssue.TOO_LONG,
                "单条录音不能超过 5 秒",
            )
        }
        val samples = pcm.samples
        val peak = samples.maxOf { abs(it.toInt()) } / Short.MAX_VALUE.toDouble()
        val rootMeanSquare = sqrt(
            samples.sumOf { sample ->
                val normalized = sample / Short.MAX_VALUE.toDouble()
                normalized * normalized
            } / samples.size,
        )
        if (peak < thresholds.minimumPeak ||
            rootMeanSquare < thresholds.minimumRootMeanSquare
        ) {
            return AliasRecordingQualityResult.Rejected(
                AliasRecordingQualityIssue.TOO_QUIET,
                "没有听清录音内容，请靠近麦克风重新录制",
            )
        }
        val clippedRatio = samples.count { abs(it.toInt()) >= CLIPPING_SAMPLE }
            .toDouble() / samples.size
        if (clippedRatio > MAX_CLIPPED_RATIO) {
            return AliasRecordingQualityResult.Rejected(
                AliasRecordingQualityIssue.TOO_LOUD,
                "声音太大，请稍微远离手机重新录制",
            )
        }
        val frameSize = pcm.sampleRate * FRAME_DURATION_MS / 1_000
        val frameCount = samples.size / frameSize
        var activeFrames = 0
        for (frameIndex in 0 until frameCount) {
            val start = frameIndex * frameSize
            var energy = 0.0
            for (sampleIndex in start until start + frameSize) {
                val normalized = samples[sampleIndex] / Short.MAX_VALUE.toDouble()
                energy += normalized * normalized
            }
            if (sqrt(energy / frameSize) >= thresholds.activeFrameRootMeanSquare) {
                activeFrames++
            }
        }
        val activeRatio = if (frameCount == 0) 0.0 else activeFrames.toDouble() / frameCount
        if (activeRatio < thresholds.minimumActiveFrameRatio) {
            return AliasRecordingQualityResult.Rejected(
                AliasRecordingQualityIssue.TOO_LITTLE_SPEECH,
                "录音中停顿太多，请连续说出录音内容",
            )
        }
        return AliasRecordingQualityResult.Passed(
            durationMs = pcm.durationMs,
            peak = peak,
            rootMeanSquare = rootMeanSquare,
            activeFrameRatio = activeRatio,
        )
    }

    companion object {
        const val MIN_DURATION_MS = 1_000
        const val MAX_DURATION_MS = 5_000
        const val MAX_DURATION_DIFFERENCE_MS = 50
        const val FRAME_DURATION_MS = 20
        const val CLIPPING_SAMPLE = 32_700
        const val MAX_CLIPPED_RATIO = 0.02

        private val STANDARD_THRESHOLDS = QualityThresholds(
            minimumDurationMs = MIN_DURATION_MS,
            tooShortMessage = "请完整说出录音内容，录音至少 1 秒",
            minimumPeak = 0.08,
            minimumRootMeanSquare = 0.01,
            activeFrameRootMeanSquare = 0.015,
            minimumActiveFrameRatio = 0.25,
        )

        private val EMULATOR_THRESHOLDS = QualityThresholds(
            minimumDurationMs = MIN_DURATION_MS,
            tooShortMessage = "请完整说出录音内容，录音至少 1 秒",
            minimumPeak = 0.025,
            minimumRootMeanSquare = 0.0025,
            activeFrameRootMeanSquare = 0.003,
            minimumActiveFrameRatio = 0.15,
        )

        private val VOICE_TEMPLATE_THRESHOLDS = QualityThresholds(
            minimumDurationMs = 300,
            tooShortMessage = "有效发音太短，请完整说出当前称呼或指令",
            minimumPeak = 0.04,
            minimumRootMeanSquare = 0.004,
            activeFrameRootMeanSquare = 0.006,
            minimumActiveFrameRatio = 0.15,
        )

        /**
         * 创建仅用于 Debug 模拟器的低电平兼容实例。
         *
         * <p>该档位仍拒绝静音、过短、严重削波和有效声音占比不足的录音，
         * 不得用于 Release 或真实设备。
         *
         * @return 模拟器兼容质量分析器
         */
        internal fun emulatorCompatible(): AliasRecordingQualityAnalyzer =
            AliasRecordingQualityAnalyzer(EMULATOR_THRESHOLDS)

        /**
         * 创建仅用于称呼和固定安全指令的短语音档位。
         *
         * 该档位只接收已经裁掉首尾静音的音频，允许自然短词低至 300 毫秒；
         * 训练语料采集仍使用标准档位，不受影响。
         */
        internal fun voiceTemplateCompatible(): AliasRecordingQualityAnalyzer =
            AliasRecordingQualityAnalyzer(VOICE_TEMPLATE_THRESHOLDS)
    }

    private data class QualityThresholds(
        val minimumDurationMs: Int,
        val tooShortMessage: String,
        val minimumPeak: Double,
        val minimumRootMeanSquare: Double,
        val activeFrameRootMeanSquare: Double,
        val minimumActiveFrameRatio: Double,
    )
}

/**
 * 称呼录音质量预检结果。
 */
sealed interface AliasRecordingQualityResult {
    data class Passed(
        val durationMs: Int,
        val peak: Double,
        val rootMeanSquare: Double,
        val activeFrameRatio: Double,
    ) : AliasRecordingQualityResult

    data class Rejected(
        val issue: AliasRecordingQualityIssue,
        val message: String,
    ) : AliasRecordingQualityResult
}

/**
 * 本地录音质量问题。
 */
enum class AliasRecordingQualityIssue {
    INVALID_AUDIO,
    TOO_SHORT,
    TOO_LONG,
    TOO_QUIET,
    TOO_LOUD,
    TOO_LITTLE_SPEECH,
}
