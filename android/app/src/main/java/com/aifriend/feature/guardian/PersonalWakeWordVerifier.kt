package com.aifriend.feature.guardian

import com.aifriend.core.audio.CapturedAudio
import com.aifriend.core.audio.VoiceTemplateRecordingNormalizer
import com.aifriend.core.audio.VoiceTemplateRecordingResult
import com.aifriend.core.audio.WavPcmCodec
import com.aifriend.core.voice.LocalVoiceTemplateCandidate
import com.aifriend.core.voice.LocalVoiceTemplateCoordinator
import com.aifriend.core.voice.LocalVoiceTemplateEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/** 固定“小友”内容识别之后的个人发音模板校验端口，不用于识别说话人身份。 */
interface PersonalWakeWordVerifier {
    suspend fun prepare(): Boolean
    fun matches(samples: ShortArray, repetitions: Int = 1): Boolean
    fun close()
}

/** 使用当前 owner 的本机 Keystore 密文模板校验一次“小友”发音。 */
@Singleton
class LocalPersonalWakeWordVerifier @Inject constructor(
    private val coordinator: LocalVoiceTemplateCoordinator,
    private val engine: LocalVoiceTemplateEngine,
    private val normalizer: VoiceTemplateRecordingNormalizer,
) : PersonalWakeWordVerifier {
    private var template: LocalVoiceTemplateCandidate? = null

    override suspend fun prepare(): Boolean {
        val loaded = coordinator.loadWakeWord()
        synchronized(lock) {
            template?.clear()
            template = loaded
            return loaded != null
        }
    }

    override fun matches(samples: ShortArray, repetitions: Int): Boolean = synchronized(lock) {
        val activeTemplate = template ?: return@synchronized false
        if (repetitions == 1) return@synchronized matchesSingle(samples, activeTemplate)
        if (repetitions != 2) return@synchronized false
        val parts = splitDoubleWake(samples) ?: return@synchronized false
        try {
            parts.all { part -> matchesSingle(part, activeTemplate) }
        } finally {
            parts.forEach { it.fill(0) }
        }
    }

    private fun matchesSingle(
        samples: ShortArray,
        activeTemplate: LocalVoiceTemplateCandidate,
    ): Boolean {
        if (samples.size !in MINIMUM_SAMPLES..MAXIMUM_SAMPLES) return false
        val pcm = ByteArray(samples.size * Short.SIZE_BYTES)
        var captured: CapturedAudio? = null
        var normalized: CapturedAudio? = null
        return try {
            ByteBuffer.wrap(pcm)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .put(samples)
            captured = CapturedAudio(
                wavBytes = WavPcmCodec.encodeMono16(pcm),
                durationMs = samples.size * 1_000 / WavPcmCodec.SAMPLE_RATE,
            )
            val result = normalizer.normalize(captured)
            if (result !is VoiceTemplateRecordingResult.Passed) return false
            normalized = result.audio
            engine.classify(
                normalized.wavBytes,
                mapOf(WAKE_TEMPLATE_MATCH_ID to activeTemplate),
            ) == WAKE_TEMPLATE_MATCH_ID
        } catch (_: Exception) {
            false
        } finally {
            normalized?.clear()
            captured?.clear()
            pcm.fill(0)
        }
    }

    /**
     * Vosk 可能把自然说出的“小友、小友”作为一个最终结果返回。这里只在本机内存中
     * 按两段有效发音之间的最大静音间隔切分；没有明显停顿时使用有效发音中点。
     */
    private fun splitDoubleWake(samples: ShortArray): List<ShortArray>? {
        if (samples.size !in (MINIMUM_SAMPLES * 2)..MAXIMUM_SAMPLES) return null
        val frameSize = WavPcmCodec.SAMPLE_RATE * FRAME_DURATION_MS / 1_000
        val frameCount = samples.size / frameSize
        if (frameCount < 2) return null
        val activeFrames = ArrayList<Int>(frameCount)
        for (frame in 0 until frameCount) {
            val start = frame * frameSize
            var energy = 0.0
            for (index in start until start + frameSize) {
                val normalized = samples[index] / Short.MAX_VALUE.toDouble()
                energy += normalized * normalized
            }
            if (sqrt(energy / frameSize) >= ACTIVE_FRAME_ROOT_MEAN_SQUARE) {
                activeFrames += frame
            }
        }
        if (activeFrames.size < 2) return null
        val activeMiddle = (activeFrames.first() + activeFrames.last() + 1) * frameSize / 2
        val splitSample = activeFrames.zipWithNext()
            .maxWithOrNull(
                compareBy<Pair<Int, Int>> { (left, right) -> right - left }
                    .thenBy { (left, right) ->
                        -kotlin.math.abs(((left + right + 1) * frameSize / 2) - activeMiddle)
                    },
            )
            ?.let { (left, right) -> (left + right + 1) * frameSize / 2 }
            ?.takeIf { split ->
                split >= MINIMUM_SAMPLES && samples.size - split >= MINIMUM_SAMPLES
            }
            ?: activeMiddle
        if (splitSample < MINIMUM_SAMPLES || samples.size - splitSample < MINIMUM_SAMPLES) {
            return null
        }
        return listOf(
            samples.copyOfRange(0, splitSample),
            samples.copyOfRange(splitSample, samples.size),
        )
    }

    override fun close() = synchronized(lock) {
        template?.clear()
        template = null
    }

    private companion object {
        const val WAKE_TEMPLATE_MATCH_ID = "personal-wake-word"
        const val MINIMUM_SAMPLES = WavPcmCodec.SAMPLE_RATE * 300 / 1_000
        const val MAXIMUM_SAMPLES = WavPcmCodec.SAMPLE_RATE * 5
        const val FRAME_DURATION_MS = 20
        const val ACTIVE_FRAME_ROOT_MEAN_SQUARE = 0.006
        val lock = Any()
    }
}
