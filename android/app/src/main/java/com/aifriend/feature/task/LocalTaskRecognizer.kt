package com.aifriend.feature.task

import com.aifriend.core.audio.CapturedAudio
import com.aifriend.core.audio.WavPcmCodec
import com.aifriend.core.voice.BasicExperienceDialectPackage
import com.aifriend.feature.guardian.GuardianWakeModelManifest
import com.aifriend.feature.guardian.VoskModelAssetInstaller
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/** Android 本机基础任务识别结果；只在当前请求内存中使用。 */
data class LocalTaskRecognition(
    val transcript: String,
    val confidence: Double,
    val modelVersion: String,
    val modelArchiveSha256: String,
    val words: List<LocalTaskRecognizedWord>,
)

/** Android 本机基础识别的词级时间戳。 */
data class LocalTaskRecognizedWord(
    val text: String,
    val startMs: Int,
    val endMs: Int,
    val confidence: Double,
)

/** 仅处理当前内存 WAV 的本机基础识别器。 */
interface LocalTaskRecognizer {
    suspend fun recognize(audio: CapturedAudio): LocalTaskRecognition
}

/**
 * 复用 APK 内固定 Vosk 中文模型生成临时文字和词级时间。
 *
 * 该结果不是联系人身份或动作授权；识别器不联网、不保存音频，也不写日志。
 */
@Singleton
class VoskLocalTaskRecognizer @Inject constructor(
    private val installer: VoskModelAssetInstaller,
) : LocalTaskRecognizer {
    override suspend fun recognize(audio: CapturedAudio): LocalTaskRecognition =
        withContext(Dispatchers.Default) {
            val pcm = WavPcmCodec.decodeMono16(audio.wavBytes)
            val modelDirectory = installer.install()
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val model = Model(modelDirectory.absolutePath)
            val recognizer = Recognizer(model, WavPcmCodec.SAMPLE_RATE.toFloat())
            try {
                recognizer.setWords(true)
                recognizer.acceptWaveForm(pcm.samples, pcm.samples.size)
                LocalTaskRecognitionParser.parse(recognizer.finalResult, audio.durationMs)
            } finally {
                runCatching { recognizer.close() }
                runCatching { model.close() }
                pcm.samples.fill(0)
            }
        }

}

/** 有界解析 Vosk JSON 的纯函数，不接触文件、网络或日志。 */
internal object LocalTaskRecognitionParser {
    fun parse(resultJson: String, durationMs: Int): LocalTaskRecognition {
        require(resultJson.length in 1..MAX_RESULT_CHARACTERS) { "本地识别结果无效" }
        val root = Json.parseToJsonElement(resultJson).jsonObject
        val transcript = root["text"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(transcript.length in 1..MAX_TRANSCRIPT_CHARACTERS) { "没有听清，请重新说" }
        val result = root["result"]?.jsonArray ?: error("没有可靠的词级时间，请重新说")
        require(result.size in 1..MAX_WORDS) { "没有可靠的词级时间，请重新说" }
        val words = result.map { element ->
            val word = element.jsonObject
            val text = word["word"]?.jsonPrimitive?.content?.trim().orEmpty()
            val start = word["start"]?.jsonPrimitive?.doubleOrNull
            val end = word["end"]?.jsonPrimitive?.doubleOrNull
            val confidence = word["conf"]?.jsonPrimitive?.doubleOrNull
            require(text.length in 1..MAX_WORD_CHARACTERS) { "本地识别词无效" }
            require(start != null && end != null && confidence != null) { "本地识别时间无效" }
            val startMs = (start * 1_000.0).roundToInt()
            val endMs = (end * 1_000.0).roundToInt()
            require(startMs >= 0 && endMs > startMs && endMs <= durationMs + TIME_TOLERANCE_MS) {
                "本地识别时间超出录音范围"
            }
            require(confidence in 0.0..1.0) { "本地识别置信度无效" }
            LocalTaskRecognizedWord(text, startMs, endMs.coerceAtMost(durationMs), confidence)
        }
        return LocalTaskRecognition(
            transcript = transcript,
            confidence = words.map(LocalTaskRecognizedWord::confidence).average(),
            modelVersion = GuardianWakeModelManifest.MODEL_VERSION,
            modelArchiveSha256 = GuardianWakeModelManifest.ARCHIVE_SHA256,
            words = words,
        )
    }

    private const val MAX_RESULT_CHARACTERS = 65_536
    private const val MAX_TRANSCRIPT_CHARACTERS = 1_000
    private const val MAX_WORDS = 200
    private const val MAX_WORD_CHARACTERS = 40
    private const val TIME_TOLERANCE_MS = 100
}

/** 基础体验固定版本上下文；不表示已有正式武冈话模型。 */
object BasicExperienceTaskContext {
    const val DIALECT_CODE = BasicExperienceDialectPackage.DIALECT_CODE
    const val PACKAGE_VERSION = BasicExperienceDialectPackage.PACKAGE_VERSION
    const val ACOUSTIC_MODEL_VERSION = BasicExperienceDialectPackage.ACOUSTIC_MODEL_VERSION
    const val THRESHOLD_VERSION = BasicExperienceDialectPackage.THRESHOLD_VERSION
    const val ASR_MODEL_VERSION = BasicExperienceDialectPackage.ASR_MODEL_VERSION
    const val FUSION_RULE_VERSION = BasicExperienceDialectPackage.FUSION_RULE_VERSION
}
