package com.aifriend.core.voice

import com.aifriend.core.audio.CapturedAudio
import com.aifriend.core.audio.VoiceTemplateRecordingNormalizer
import com.aifriend.core.audio.VoiceTemplateRecordingResult
import com.aifriend.core.audio.WavPcmCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TaskDecisionRuntimeAudioTest {

    private val engine = MfccDtwLocalVoiceTemplateEngine(
        DialectPackageRegistry { BasicExperienceDialectPackage.create() },
    )
    private val normalizer = VoiceTemplateRecordingNormalizer()

    @Test
    fun `五秒确认录音中的自然短词必须先裁静音再匹配个人模板`() {
        val rawConfirm = utteranceInFiveSeconds(listOf(310.0, 520.0, 760.0))
        val rawReject = utteranceInFiveSeconds(listOf(780.0, 430.0, 260.0))
        val confirm = normalize(rawConfirm)
        val reject = normalize(rawReject)
        val confirmTemplate = engine.enroll(confirm.wavBytes, confirm.wavBytes)
        val rejectTemplate = engine.enroll(reject.wavBytes, reject.wavBytes)
        try {
            assertThrows(LocalVoiceTemplateException::class.java) {
                engine.classify(
                    rawConfirm.wavBytes,
                    mapOf("CONFIRM" to confirmTemplate, "REJECT" to rejectTemplate),
                )
            }
            val runtimeConfirm = normalize(rawConfirm)
            try {
                assertEquals(
                    "CONFIRM",
                    engine.classify(
                        runtimeConfirm.wavBytes,
                        mapOf("CONFIRM" to confirmTemplate, "REJECT" to rejectTemplate),
                    ),
                )
            } finally {
                runtimeConfirm.clear()
            }
        } finally {
            rawConfirm.clear()
            rawReject.clear()
            confirm.clear()
            reject.clear()
            confirmTemplate.clear()
            rejectTemplate.clear()
        }
    }

    private fun normalize(audio: CapturedAudio): CapturedAudio =
        when (val result = normalizer.normalize(audio)) {
            is VoiceTemplateRecordingResult.Passed -> result.audio
            is VoiceTemplateRecordingResult.Rejected ->
                error("合成短词未通过生产预处理: ${result.issue}")
        }

    private fun utteranceInFiveSeconds(frequencies: List<Double>): CapturedAudio {
        val sampleRate = WavPcmCodec.SAMPLE_RATE
        val samples = ShortArray(sampleRate * 5)
        val speechStart = sampleRate * 2
        val speechSamples = sampleRate * 7 / 10
        for (offset in 0 until speechSamples) {
            val section = (offset * frequencies.size / speechSamples)
                .coerceAtMost(frequencies.lastIndex)
            val envelope = sin(PI * offset / speechSamples).coerceAtLeast(0.0)
            samples[speechStart + offset] = (
                sin(2.0 * PI * frequencies[section] * offset / sampleRate) *
                    envelope * 12_000.0
                ).toInt().toShort()
        }
        val pcm = ByteArray(samples.size * Short.SIZE_BYTES)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
        samples.fill(0)
        val wav = WavPcmCodec.encodeMono16(pcm)
        pcm.fill(0)
        return CapturedAudio(wav, 5_000)
    }
}
