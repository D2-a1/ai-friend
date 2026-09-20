package com.aifriend.feature.task

import com.aifriend.contract.model.AliasCompatibility
import com.aifriend.contract.model.ContactStatus
import com.aifriend.contract.model.TaskCandidate
import com.aifriend.core.audio.CapturedAudio
import com.aifriend.core.audio.VoiceTemplateRecordingNormalizer
import com.aifriend.core.audio.VoiceTemplateRecordingResult
import com.aifriend.core.audio.WavPcmCodec
import com.aifriend.core.voice.BasicExperienceDialectPackage
import com.aifriend.core.voice.LocalVoiceTemplateCoordinator
import com.aifriend.core.voice.LocalVoiceTemplateEngine
import com.aifriend.core.voice.TaskDecisionTemplateType
import com.aifriend.feature.contact.ContactRepository
import com.aifriend.feature.guardian.GuardianWakeModelManifest
import com.aifriend.feature.guardian.VoskModelAssetInstaller
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
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

    /** 系统播报完成后，只识别个人模板或普通确认/否认决定。 */
    suspend fun recognizeConfirmation(
        audio: CapturedAudio,
        expectedAction: com.aifriend.contract.model.ConfirmationAction,
    ): VoiceConfirmationDecision

    /** 系统播报候选人后，识别称呼、序号、重听或取消。 */
    suspend fun recognizeCandidateSelection(
        audio: CapturedAudio,
        candidates: List<TaskCandidate>,
    ): VoiceCandidateSelection = VoiceCandidateSelection.UNKNOWN
}

/**
 * 复用 APK 内固定 Vosk 中文模型生成临时文字和词级时间。
 *
 * 该结果不是联系人身份或动作授权；识别器不联网、不保存音频，也不写日志。
 */
@Singleton
class VoskLocalTaskRecognizer @Inject constructor(
    private val installer: VoskModelAssetInstaller,
    private val vocabularyProvider: TaskRecognitionVocabularyProvider,
    private val templateCoordinator: LocalVoiceTemplateCoordinator,
    private val templateEngine: LocalVoiceTemplateEngine,
    private val templateRecordingNormalizer: VoiceTemplateRecordingNormalizer,
    private val safetyCommandMatcher: SafetyCommandMatcher,
) : LocalTaskRecognizer {
    override suspend fun recognize(audio: CapturedAudio): LocalTaskRecognition =
        withContext(Dispatchers.Default) {
            try {
                val pcm = WavPcmCodec.decodeMono16(audio.wavBytes)
                val modelDirectory = installer.install()
                val aliases = vocabularyProvider.compatibleAliases()
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val model = Model(modelDirectory.absolutePath)
                try {
                    val constrained = TaskCallGrammar.build(aliases)?.let { grammar ->
                        runCatching {
                            recognize(model, pcm.samples, audio.durationMs, grammar)
                        }.getOrNull()?.takeIf { recognition ->
                            TaskCallGrammar.isRecognizedCall(recognition.transcript, aliases)
                        }
                    }
                    constrained ?: recognize(model, pcm.samples, audio.durationMs)
                } finally {
                    runCatching { model.close() }
                    pcm.samples.fill(0)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: LocalTaskRecognitionException) {
                throw exception
            } catch (_: LinkageError) {
                throw LocalTaskRecognitionException("本机语音识别组件不可用，请重新打开应用")
            } catch (_: Exception) {
                throw LocalTaskRecognitionException("本机语音识别没有完成，请重新说")
            }
        }

    override suspend fun recognizeConfirmation(
        audio: CapturedAudio,
        expectedAction: com.aifriend.contract.model.ConfirmationAction,
    ): VoiceConfirmationDecision = withContext(Dispatchers.Default) {
        val personalDecision = recognizePersonalConfirmation(audio)
        // 自由词表转写只供 TaskViewModel 判断是否为完整口头纠错。确认决定必须
        // 重新走有限语法，否则短词“确认”可能被自由词表误听成“取消”。
        val textDecision = recognizeConfirmationWithVosk(audio)
        val safetyDecision = recognizeSafetyCommand(audio, expectedAction)
        fuseConfirmationDecisions(textDecision, personalDecision, safetyDecision)
    }

    private suspend fun recognizeSafetyCommand(
        audio: CapturedAudio,
        expectedAction: com.aifriend.contract.model.ConfirmationAction,
    ): VoiceConfirmationDecision {
        var normalizedAudio: CapturedAudio? = null
        return try {
            when (val normalized = templateRecordingNormalizer.normalize(audio)) {
                is VoiceTemplateRecordingResult.Rejected -> VoiceConfirmationDecision.UNKNOWN
                is VoiceTemplateRecordingResult.Passed -> {
                    normalizedAudio = normalized.audio
                    safetyCommandMatcher.decide(normalized.audio.wavBytes, expectedAction)
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            VoiceConfirmationDecision.UNAVAILABLE
        } catch (_: LinkageError) {
            VoiceConfirmationDecision.UNAVAILABLE
        } finally {
            normalizedAudio?.clear()
        }
    }

    private suspend fun recognizePersonalConfirmation(
        audio: CapturedAudio,
    ): VoiceConfirmationDecision {
        val personalTemplates = try {
            templateCoordinator.loadTaskDecisionTemplates()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            return VoiceConfirmationDecision.UNAVAILABLE
        } catch (_: LinkageError) {
            return VoiceConfirmationDecision.UNAVAILABLE
        }
        if (personalTemplates.isEmpty()) return VoiceConfirmationDecision.UNAVAILABLE
        var normalizedAudio: CapturedAudio? = null
        return try {
            when (val normalized = templateRecordingNormalizer.normalize(audio)) {
                is VoiceTemplateRecordingResult.Rejected -> VoiceConfirmationDecision.UNKNOWN
                is VoiceTemplateRecordingResult.Passed -> {
                    normalizedAudio = normalized.audio
                    when (
                        templateEngine.classify(
                            normalized.audio.wavBytes,
                            personalTemplates.associate {
                                it.type.name to it.candidate
                            },
                        )
                    ) {
                        TaskDecisionTemplateType.CONFIRM.name ->
                            VoiceConfirmationDecision.CONFIRM
                        TaskDecisionTemplateType.REJECT.name ->
                            VoiceConfirmationDecision.REJECT
                        else -> VoiceConfirmationDecision.UNKNOWN
                    }
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            VoiceConfirmationDecision.UNAVAILABLE
        } catch (_: LinkageError) {
            VoiceConfirmationDecision.UNAVAILABLE
        } finally {
            normalizedAudio?.clear()
            personalTemplates.forEach { it.clear() }
        }
    }

    private suspend fun recognizeConfirmationWithVosk(
        audio: CapturedAudio,
    ): VoiceConfirmationDecision {
        var samples: ShortArray? = null
        return try {
            val pcm = WavPcmCodec.decodeMono16(audio.wavBytes)
            samples = pcm.samples
            val modelDirectory = installer.install()
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val model = Model(modelDirectory.absolutePath)
            try {
                val recognition = recognize(
                    model,
                    pcm.samples,
                    audio.durationMs,
                    VoiceConfirmationGrammar.grammar,
                )
                VoiceConfirmationGrammar.classify(recognition.transcript)
            } finally {
                runCatching { model.close() }
            }
        } catch (exception: Exception) {
            confirmationRecognitionFailureDecision(exception)
        } catch (_: LinkageError) {
            VoiceConfirmationDecision.UNAVAILABLE
        } finally {
            samples?.fill(0)
        }
    }

    override suspend fun recognizeCandidateSelection(
        audio: CapturedAudio,
        candidates: List<TaskCandidate>,
    ): VoiceCandidateSelection = withContext(Dispatchers.Default) {
        if (candidates.isEmpty()) return@withContext VoiceCandidateSelection.UNAVAILABLE
        var samples: ShortArray? = null
        try {
            val pcm = WavPcmCodec.decodeMono16(audio.wavBytes)
            samples = pcm.samples
            val modelDirectory = installer.install()
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val model = Model(modelDirectory.absolutePath)
            try {
                val grammar = VoiceCandidateSelectionGrammar.build(candidates)
                val recognition = recognize(model, pcm.samples, audio.durationMs, grammar)
                VoiceCandidateSelectionGrammar.classify(recognition.transcript, candidates)
            } finally {
                runCatching { model.close() }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: NoSpeechRecognizedException) {
            VoiceCandidateSelection.UNKNOWN
        } catch (_: Exception) {
            VoiceCandidateSelection.UNAVAILABLE
        } catch (_: LinkageError) {
            VoiceCandidateSelection.UNAVAILABLE
        } finally {
            samples?.fill(0)
        }
    }

    private fun recognize(
        model: Model,
        samples: ShortArray,
        durationMs: Int,
        grammar: String? = null,
    ): LocalTaskRecognition {
        val recognizer = if (grammar == null) {
            Recognizer(model, WavPcmCodec.SAMPLE_RATE.toFloat())
        } else {
            Recognizer(model, WavPcmCodec.SAMPLE_RATE.toFloat(), grammar)
        }
        return try {
            recognizer.setWords(true)
            val completedResults = mutableListOf<String>()
            var offset = 0
            while (offset < samples.size) {
                val end = (offset + RECOGNITION_CHUNK_SAMPLES).coerceAtMost(samples.size)
                val chunk = samples.copyOfRange(offset, end)
                try {
                    if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                        completedResults += recognizer.result
                    }
                } finally {
                    chunk.fill(0)
                }
                offset = end
            }
            completedResults += recognizer.finalResult
            LocalTaskRecognitionParser.parseSegments(completedResults, durationMs)
        } finally {
            runCatching { recognizer.close() }
        }
    }

    private companion object {
        const val RECOGNITION_CHUNK_SAMPLES = WavPcmCodec.SAMPLE_RATE / 4
    }
}

/** 候选联系人语音选择；任何非唯一结果都不能推进任务。 */
sealed interface VoiceCandidateSelection {
    data class Selected(val candidateId: String) : VoiceCandidateSelection
    data object REPEAT : VoiceCandidateSelection
    data object CANCEL : VoiceCandidateSelection
    data object UNKNOWN : VoiceCandidateSelection
    data object UNAVAILABLE : VoiceCandidateSelection
}

/** 系统播报后的有限语音决定；UNKNOWN 与 UNAVAILABLE 永远不能执行动作。 */
enum class VoiceConfirmationDecision {
    CONFIRM,
    REJECT,
    REPEAT,
    UNKNOWN,
    UNAVAILABLE,
}

/**
 * 合并普通话文字识别与个人短词模板；两路结论冲突时必须重新询问，不能误取消或执行。
 *
 * 单路正常但未命中属于可重听的 UNKNOWN；只有两路都不可用才报告组件不可用。
 */
internal fun fuseConfirmationDecisions(
    vararg decisions: VoiceConfirmationDecision,
): VoiceConfirmationDecision {
    val finalDecisions = decisions.filter {
        it == VoiceConfirmationDecision.CONFIRM ||
            it == VoiceConfirmationDecision.REJECT ||
            it == VoiceConfirmationDecision.REPEAT
    }.distinct()
    if (finalDecisions.size > 1) return VoiceConfirmationDecision.UNKNOWN
    if (finalDecisions.size == 1) return finalDecisions.single()
    return if (decisions.isNotEmpty() &&
        decisions.all { it == VoiceConfirmationDecision.UNAVAILABLE }
    ) {
        VoiceConfirmationDecision.UNAVAILABLE
    } else {
        VoiceConfirmationDecision.UNKNOWN
    }
}

/** 区分可重听的无语音结果、必须传播的取消，以及应立即失败关闭的识别器异常。 */
internal fun confirmationRecognitionFailureDecision(
    exception: Exception,
): VoiceConfirmationDecision = when (exception) {
    is CancellationException -> throw exception
    is NoSpeechRecognizedException -> VoiceConfirmationDecision.UNKNOWN
    else -> VoiceConfirmationDecision.UNAVAILABLE
}

/** 播报完整任务后允许老人直接说出的有限确认词；页面说明与识别器共用此定义。 */
internal object TaskConfirmationPhraseCatalog {
    val confirmPhrases: List<String> = listOf("确认")
    val rejectPhrases: List<String> = listOf("否认", "拒绝", "取消", "不确认")
}

/** 只接受完整短词，避免把任务内容或系统回声误判为确认。 */
internal object VoiceConfirmationGrammar {
    private val phrases = (
        TaskConfirmationPhraseCatalog.confirmPhrases + TaskConfirmationPhraseCatalog.rejectPhrases
    ).flatMap { phrase -> listOf(phrase.toList().joinToString(" "), phrase) } + "[unk]"
    val grammar: String = Json.encodeToString(phrases)

    fun classify(transcript: String): VoiceConfirmationDecision {
        val normalized = transcript.filterNot(Char::isWhitespace)
        return when {
            normalized in TaskConfirmationPhraseCatalog.confirmPhrases ->
                VoiceConfirmationDecision.CONFIRM
            normalized in TaskConfirmationPhraseCatalog.rejectPhrases ->
                VoiceConfirmationDecision.REJECT
            else -> VoiceConfirmationDecision.UNKNOWN
        }
    }
}

/** 候选称呼和序号的有限语法；同名命中或越界序号一律返回 UNKNOWN。 */
internal object VoiceCandidateSelectionGrammar {
    private val repeatPhrases = setOf("再听一遍", "重新说一遍", "重听")
    private val cancelPhrases = setOf("取消", "不要了", "否认")
    private val ordinals = listOf(
        setOf("第一个", "第一位", "一"),
        setOf("第二个", "第二位", "二"),
        setOf("第三个", "第三位", "三"),
    )

    fun build(candidates: List<TaskCandidate>): String {
        val labels = candidates.flatMap { candidate ->
            listOf(candidate.contact.displayName, candidate.contact.alias)
        }.map(::normalize).filter(String::isNotBlank)
        val phrases = (labels + ordinals.take(candidates.size).flatten() +
            repeatPhrases + cancelPhrases)
            .flatMap { phrase -> listOf(phrase, phrase.toList().joinToString(" ")) }
            .distinct() + "[unk]"
        return Json.encodeToString(phrases)
    }

    fun classify(
        transcript: String,
        candidates: List<TaskCandidate>,
    ): VoiceCandidateSelection {
        val normalized = normalize(transcript)
        if (normalized in repeatPhrases) return VoiceCandidateSelection.REPEAT
        if (normalized in cancelPhrases) return VoiceCandidateSelection.CANCEL
        ordinals.forEachIndexed { index, phrases ->
            if (normalized in phrases) {
                return candidates.getOrNull(index)?.let {
                    VoiceCandidateSelection.Selected(it.candidateId)
                } ?: VoiceCandidateSelection.UNKNOWN
            }
        }
        val matches = candidates.filter { candidate ->
            normalized == normalize(candidate.contact.displayName) ||
                normalized == normalize(candidate.contact.alias)
        }
        return matches.singleOrNull()?.let {
            VoiceCandidateSelection.Selected(it.candidateId)
        } ?: VoiceCandidateSelection.UNKNOWN
    }

    private fun normalize(value: String): String =
        value.filter { it.isLetterOrDigit() }
}

/** 个人确认模板是否已经完整录制，供设置页和回归测试使用。 */
suspend fun LocalVoiceTemplateCoordinator.hasCompleteTaskDecisionTemplates(): Boolean {
    val templates = loadTaskDecisionTemplates()
    return try {
        templates.map { it.type }.toSet() == TaskDecisionTemplateType.entries.toSet()
    } finally {
        templates.forEach { it.clear() }
    }
}

internal class LocalTaskRecognitionException(message: String) : IllegalStateException(message)

/** Vosk 正常返回但没有可识别语音；允许确认阶段有限重听。 */
internal class NoSpeechRecognizedException : IllegalArgumentException("没有听清，请重新说")

/** 只向受限通话识别提供当前用户已兼容的联系人称呼；失败时回退自由识别。 */
@Singleton
class TaskRecognitionVocabularyProvider @Inject constructor(
    private val contactRepository: ContactRepository,
) {
    suspend fun compatibleAliases(): Set<String> = runCatching {
        contactRepository.list(status = ContactStatus.ACTIVE).items
            .asSequence()
            .flatMap { contact -> contact.aliases.orEmpty().asSequence() }
            .filter { alias -> alias.compatibility == AliasCompatibility.COMPATIBLE }
            .map { alias -> alias.displayText.trim() }
            .filter(TaskCallGrammar::isSafeAlias)
            .distinct()
            .take(TaskCallGrammar.MAXIMUM_ALIASES)
            .toSet()
    }.getOrDefault(emptySet())
}

/**
 * 为已登记称呼构建受限通话语法，降低武冈话口音被通用普通话模型自由转写错的概率。
 * 语法结果仍只是候选提示；服务端必须再用个人称呼声学模板完成唯一联系人复核。
 */
internal object TaskCallGrammar {
    const val MAXIMUM_ALIASES = 64
    private const val MAXIMUM_ALIAS_CHARACTERS = 20
    private val callForms = listOf(
        "给%s打电话",
        "打电话给%s",
        "给%s语音通话",
        "和%s语音通话",
        "给%s视频通话",
        "和%s视频通话",
        "给%s打视频",
    )
    private val callMarkers = listOf("打电话", "语音通话", "视频通话", "打视频")
    private val wildcardCallForms = listOf(
        "给 [unk] 打 电 话",
        "打 电 话 给 [unk]",
        "给 [unk] 语 音 通 话",
        "和 [unk] 语 音 通 话",
        "给 [unk] 视 频 通 话",
        "和 [unk] 视 频 通 话",
        "给 [unk] 打 视 频",
    )

    fun build(aliases: Set<String>): String? {
        val safeAliases = aliases.asSequence()
            .map(String::trim)
            .filter(::isSafeAlias)
            .distinct()
            .take(MAXIMUM_ALIASES)
            .toList()
        if (safeAliases.isEmpty()) return null
        val phrases = safeAliases.flatMap { alias ->
            callForms.flatMap { form ->
                val phrase = form.format(alias)
                listOf(tokenize(phrase), phraseTokens(phrase, alias))
            }
        } + wildcardCallForms + callMarkers.map(::tokenize) + "[unk]"
        return Json.encodeToString(phrases.distinct())
    }

    fun isRecognizedCall(transcript: String, aliases: Set<String>): Boolean {
        val normalized = normalize(transcript)
        return aliases.any(::isSafeAlias) &&
            normalized.isNotEmpty() &&
            callMarkers.any(normalized::contains)
    }

    fun isSafeAlias(alias: String): Boolean {
        val value = alias.trim()
        return value.length in 1..MAXIMUM_ALIAS_CHARACTERS &&
            value.none { character ->
                character.isISOControl() || character == '[' || character == ']'
            }
    }

    private fun tokenize(value: String): String = value.toList().joinToString(" ")

    private fun phraseTokens(value: String, alias: String): String = value
        .replace(alias, " $alias ")
        .replace("打电话", " 打 电 话 ")
        .replace("语音通话", " 语 音 通 话 ")
        .replace("视频通话", " 视 频 通 话 ")
        .replace("打视频", " 打 视 频 ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun normalize(value: String): String = value.filter { character ->
        character.isLetterOrDigit()
    }
}
/** 有界解析 Vosk JSON 的纯函数，不接触文件、网络或日志。 */
internal object LocalTaskRecognitionParser {
    fun parse(resultJson: String, durationMs: Int): LocalTaskRecognition =
        parseSegments(listOf(resultJson), durationMs)

    fun parseSegments(
        resultJsons: List<String>,
        durationMs: Int,
    ): LocalTaskRecognition {
        require(durationMs > 0) { "录音时长无效" }
        require(resultJsons.size in 1..MAX_RESULT_SEGMENTS) { "本地识别语段无效" }
        require(resultJsons.sumOf(String::length) in 1..MAX_RESULT_CHARACTERS) {
            "本地识别结果无效"
        }
        val transcripts = mutableListOf<String>()
        val parsedWords = mutableListOf<LocalTaskRecognizedWord>()
        var missingWordEvidence = false
        resultJsons.forEach { resultJson ->
            val root = Json.parseToJsonElement(resultJson).jsonObject
            val segmentTranscript = root["text"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (segmentTranscript.isEmpty()) return@forEach
            require(segmentTranscript.length <= MAX_TRANSCRIPT_CHARACTERS) {
                "本地识别文字过长"
            }
            transcripts += segmentTranscript
            val result = root["result"]?.jsonArray
            if (result == null || result.isEmpty()) {
                missingWordEvidence = true
            } else {
                require(result.size <= MAX_WORDS) { "本地识别词数过多" }
                result.forEach { element ->
                    val word = element.jsonObject
                    val text = word["word"]?.jsonPrimitive?.content?.trim().orEmpty()
                    val start = word["start"]?.jsonPrimitive?.doubleOrNull
                    val end = word["end"]?.jsonPrimitive?.doubleOrNull
                    val confidence = word["conf"]?.jsonPrimitive?.doubleOrNull
                    require(text.length in 1..MAX_WORD_CHARACTERS) { "本地识别词无效" }
                    require(start != null && end != null && confidence != null) {
                        "本地识别时间无效"
                    }
                    val startMs = (start * 1_000.0).roundToInt()
                    val endMs = (end * 1_000.0).roundToInt()
                    require(
                        startMs >= 0 && endMs > startMs &&
                            endMs <= durationMs + TIME_TOLERANCE_MS,
                    ) { "本地识别时间超出录音范围" }
                    require(confidence in 0.0..1.0) { "本地识别置信度无效" }
                    parsedWords += LocalTaskRecognizedWord(
                        text,
                        startMs,
                        endMs.coerceAtMost(durationMs),
                        confidence,
                    )
                }
            }
        }
        val transcript = transcripts.joinToString(" ").trim()
        if (transcript.isEmpty()) throw NoSpeechRecognizedException()
        require(transcript.length <= MAX_TRANSCRIPT_CHARACTERS) { "本地识别文字过长" }
        val reliableWords = !missingWordEvidence &&
            parsedWords.size in 1..MAX_WORDS &&
            parsedWords.zipWithNext().all { (left, right) -> left.endMs <= right.startMs } &&
            compact(transcript) == compact(parsedWords.joinToString(" ") { it.text })
        val words = if (reliableWords) {
            parsedWords.toList()
        } else {
            listOf(
                LocalTaskRecognizedWord(
                    text = transcript,
                    startMs = 0,
                    endMs = durationMs,
                    confidence = FALLBACK_CONFIDENCE,
                ),
            )
        }
        return LocalTaskRecognition(
            transcript = transcript,
            confidence = words.map(LocalTaskRecognizedWord::confidence).average(),
            modelVersion = GuardianWakeModelManifest.MODEL_VERSION,
            modelArchiveSha256 = GuardianWakeModelManifest.ARCHIVE_SHA256,
            words = words,
        )
    }

    private fun compact(value: String): String = value.filterNot(Char::isWhitespace)

    private const val MAX_RESULT_SEGMENTS = 64
    private const val MAX_RESULT_CHARACTERS = 65_536
    private const val MAX_TRANSCRIPT_CHARACTERS = 1_000
    private const val MAX_WORDS = 200
    private const val MAX_WORD_CHARACTERS = 40
    private const val TIME_TOLERANCE_MS = 100
    private const val FALLBACK_CONFIDENCE = 0.5
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
