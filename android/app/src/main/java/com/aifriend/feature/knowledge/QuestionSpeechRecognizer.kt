package com.aifriend.feature.knowledge

import com.aifriend.core.audio.CapturedAudio
import com.aifriend.core.audio.WavPcmCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** 消费当前问答录音，成功、失败及取消都清零传入WAV；不上传、不识别执行授权。 */
interface QuestionSpeechRecognizer {
    suspend fun recognize(audio: CapturedAudio): QuestionTranscript
}

class QuestionTranscript(val text: String) {
    override fun toString() = "QuestionTranscript[redacted]"
}
enum class QuestionSpeechFailure { NO_SPEECH, INVALID_AUDIO, TOO_LONG, UNAVAILABLE, INVALID_RESULT }
class QuestionSpeechException(val failure: QuestionSpeechFailure) : RuntimeException(failure.name)

/** 每次open的native资源仅属于本次识别，调用方负责关闭。 */
interface QuestionDecoder : AutoCloseable {
    fun accept(samples: ShortArray): String?
    fun finish(): String
}
fun interface QuestionDecoderFactory { suspend fun open(): QuestionDecoder }

class DefaultQuestionSpeechRecognizer internal constructor(private val factory: QuestionDecoderFactory) : QuestionSpeechRecognizer {
    @Inject constructor(factory: VoskQuestionDecoderFactory) : this(factory as QuestionDecoderFactory)

    override suspend fun recognize(audio: CapturedAudio): QuestionTranscript {
        // finally在withContext外；进入调度器之前就被取消也会清除传入录音。
        try {
            return withContext(Dispatchers.Default) {
                var samples: ShortArray? = null
                var decoder: QuestionDecoder? = null
                try {
                    ensureActive()
                    samples = decode(audio)
                    decoder = factory.open()
                    ensureActive()
                    val text = StringBuilder()
                    var offset = 0
                    while (offset < samples.size) {
                        ensureActive()
                        val chunk = samples.copyOfRange(offset, minOf(offset + CHUNK_SAMPLES, samples.size))
                        try { decoder.accept(chunk)?.let { append(text, it) } }
                        finally { chunk.fill(0) }
                        offset += chunk.size
                    }
                    ensureActive()
                    append(text, decoder.finish())
                    ensureActive()
                    val result = normalize(text.toString())
                    if (result.isBlank() || result.contains("[unk]")) throw QuestionSpeechException(QuestionSpeechFailure.NO_SPEECH)
                    QuestionTranscript(result)
                } catch (e: CancellationException) { throw e }
                    catch (e: QuestionSpeechException) { throw e }
                    catch (_: LinkageError) { throw QuestionSpeechException(QuestionSpeechFailure.UNAVAILABLE) }
                    catch (_: Exception) { throw QuestionSpeechException(QuestionSpeechFailure.UNAVAILABLE) }
                finally {
                    samples?.fill(0)
                    try { decoder?.close() } catch (_: Exception) { /* 不记录识别材料 */ } catch (_: LinkageError) { /* native不可用 */ }
                }
            }
        } finally { audio.clear() }
    }

    /** 只接受既有采集器标准44字节头；直接读ShortArray，避免额外未清零PCM字节副本。 */
    private fun decode(audio: CapturedAudio): ShortArray {
        val bytes = audio.wavBytes
        fun invalid(): Nothing = throw QuestionSpeechException(QuestionSpeechFailure.INVALID_AUDIO)
        if (bytes.size !in 46..MAX_WAV_BYTES || audio.durationMs !in 1..MAX_DURATION_MS) invalid()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157 || b.getInt(12) != 0x20746d66 ||
            b.getInt(36) != 0x61746164 || b.getInt(4) != bytes.size - 8 || b.getInt(16) != 16 ||
            b.getShort(20).toInt() != 1 || b.getShort(22).toInt() != 1 || b.getInt(24) != WavPcmCodec.SAMPLE_RATE ||
            b.getInt(28) != WavPcmCodec.SAMPLE_RATE * 2 || b.getShort(32).toInt() != 2 || b.getShort(34).toInt() != 16 ||
            b.getInt(40) != bytes.size - WavPcmCodec.HEADER_SIZE || (bytes.size - 44) % 2 != 0) invalid()
        val count = (bytes.size - 44) / 2
        if (count.toLong() * 1000 / WavPcmCodec.SAMPLE_RATE != audio.durationMs.toLong()) invalid()
        return ShortArray(count).also { b.position(44); b.asShortBuffer().get(it) }
    }
    private fun append(target: StringBuilder, json: String) {
        if (json.length > MAX_RESULT_CHARACTERS) throw QuestionSpeechException(QuestionSpeechFailure.INVALID_RESULT)
        val piece = try {
            val value = Json.parseToJsonElement(json).jsonObject["text"] as? JsonPrimitive
            if (value == null || !value.isString) throw IllegalArgumentException()
            value.content
        } catch (_: Exception) { throw QuestionSpeechException(QuestionSpeechFailure.INVALID_RESULT) }
        if (!validUnicode(piece)) throw QuestionSpeechException(QuestionSpeechFailure.INVALID_RESULT)
        val joined = normalize(if (target.isEmpty()) piece else "$target $piece")
        if (joined.codePointCount(0, joined.length) > 500) throw QuestionSpeechException(QuestionSpeechFailure.TOO_LONG)
        target.setLength(0); target.append(joined)
    }
    private fun normalize(value: String) = value.trim().replace(Regex("\\s+"), " ")
        .replace(Regex("(?<=\\p{IsHan}) +(?=\\p{IsHan})"), "")
    private fun validUnicode(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= value.length || !value[i + 1].isLowSurrogate()) return false
                i += 2
            } else {
                if (c.isLowSurrogate() || Character.isISOControl(c) && c !in "\n\r\t") return false
                i++
            }
        }
        return true
    }
    companion object {
        const val MAX_DURATION_MS = 30000
        private const val MAX_WAV_BYTES = 44 + 16000 * 2 * 30
        private const val CHUNK_SAMPLES = 4000
        private const val MAX_RESULT_CHARACTERS = 32768
    }
}
