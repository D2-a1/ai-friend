package com.aifriend.feature.guardian

import android.content.Context
import android.os.Build
import android.util.Log
import com.aifriend.core.audio.Pcm16RingBuffer
import com.aifriend.core.audio.WavPcmCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/** 本地双唤醒检测器的可用状态。 */
enum class GuardianWakeReadiness(val userMessage: String) {
    READY("本地双唤醒模型已就绪"),
    MODEL_ASSET_MISSING("本地双唤醒模型资源不完整，小友守护没有开启"),
    ABI_UNSUPPORTED("当前手机处理器不支持本地双唤醒，小友守护没有开启"),
    MODEL_INVALID("本地双唤醒模型校验或加载失败，小友守护没有开启"),
    PERSONAL_TEMPLATE_MISSING("请先在“我的”录制两遍“小友”，小友守护没有开启"),
}

/**
 * 休眠音频的本地唤醒端口。
 *
 * 实现只判断固定唤醒词内容，不判断说话人身份；原始 PCM 不得离开当前调用内存。
 */
interface GuardianWakeWordDetector {
    fun readiness(): GuardianWakeReadiness
    suspend fun prepare(): GuardianWakeReadiness
    /** 返回当前已通过固定词义与个人模板复核的“小友”次数，只允许 0、1 或 2。 */
    fun detect(samples: ShortArray, count: Int, elapsedRealtimeMs: Long): Int
    fun reset()
    fun close()
}

/**
 * 使用随 APK 固定发布的 Vosk 中文小模型识别“小友”。
 *
 * 模型归档先由 [VoskModelAssetInstaller] 复验固定 SHA-256 并受限解压。识别器只加载
 * 一次或连续两次“小 友”和未知词文法，不联网、不保存音频、不输出自由文本，也不用于声纹识别。
 */
@Singleton
class VoskGuardianWakeWordDetector @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val installer: VoskModelAssetInstaller,
    private val personalWakeWordVerifier: PersonalWakeWordVerifier,
) : GuardianWakeWordDetector {
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private val utteranceBuffer = Pcm16RingBuffer(
        WavPcmCodec.SAMPLE_RATE * MAXIMUM_WAKE_UTTERANCE_SECONDS,
    )

    override fun readiness(): GuardianWakeReadiness {
        if (Build.SUPPORTED_ABIS.none(SUPPORTED_ABIS::contains)) {
            return GuardianWakeReadiness.ABI_UNSUPPORTED
        }
        return runCatching {
            context.assets.open(GuardianWakeModelManifest.ARCHIVE_ASSET).use { Unit }
            GuardianWakeReadiness.READY
        }.getOrDefault(GuardianWakeReadiness.MODEL_ASSET_MISSING)
    }

    override suspend fun prepare(): GuardianWakeReadiness {
        val current = readiness()
        if (current != GuardianWakeReadiness.READY) return current
        if (!personalWakeWordVerifier.prepare()) {
            return GuardianWakeReadiness.PERSONAL_TEMPLATE_MISSING
        }
        synchronized(lock) {
            if (recognizer != null) return GuardianWakeReadiness.READY
        }
        val modelDirectory = try {
            installer.install()
        } catch (failure: Throwable) {
            return preparationFailure(GuardianWakeFailureStage.MODEL_INSTALL, failure)
        }
        return synchronized(lock) {
            if (recognizer != null) return@synchronized GuardianWakeReadiness.READY
            try {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
            } catch (failure: Throwable) {
                return@synchronized preparationFailureLocked(
                    GuardianWakeFailureStage.NATIVE_BINDING,
                    failure,
                )
            }
            val loadedModel = try {
                Model(modelDirectory.absolutePath)
            } catch (failure: Throwable) {
                return@synchronized preparationFailureLocked(
                    GuardianWakeFailureStage.MODEL_LOAD,
                    failure,
                )
            }
            val loadedRecognizer = try {
                Recognizer(
                    loadedModel,
                    WavPcmCodec.SAMPLE_RATE.toFloat(),
                    WAKE_GRAMMAR,
                )
            } catch (failure: Throwable) {
                runCatching { loadedModel.close() }
                return@synchronized preparationFailureLocked(
                    GuardianWakeFailureStage.RECOGNIZER_INITIALIZE,
                    failure,
                )
            }
            model = loadedModel
            recognizer = loadedRecognizer
            utteranceBuffer.clear()
            GuardianWakeReadiness.READY
        }
    }

    override fun detect(
        samples: ShortArray,
        count: Int,
        elapsedRealtimeMs: Long,
    ): Int = synchronized(lock) {
        if (count <= 0 || count > samples.size) return@synchronized 0
        val activeRecognizer = recognizer ?: return@synchronized 0
        utteranceBuffer.append(samples, count)
        runCatching {
            if (activeRecognizer.acceptWaveForm(samples, count)) {
                val repetitions = guardianWakePhraseRepetitions(activeRecognizer.result)
                val utterance = utteranceBuffer.snapshot()
                utteranceBuffer.clear()
                try {
                    repetitions.takeIf {
                        it > 0 && personalWakeWordVerifier.matches(utterance, it)
                    } ?: 0
                } finally {
                    utterance.fill(0)
                }
            } else {
                0
            }
        }.getOrElse {
            logWakeFailure(GuardianWakeFailureStage.DETECTION, it)
            closeLocked()
            0
        }
    }

    override fun reset() = synchronized(lock) {
        utteranceBuffer.clear()
        runCatching { recognizer?.reset() }.onFailure {
            logWakeFailure(GuardianWakeFailureStage.RESET, it)
            closeLocked()
        }
        Unit
    }

    override fun close() = synchronized(lock) {
        closeLocked()
    }

    private fun closeLocked() {
        utteranceBuffer.clear()
        personalWakeWordVerifier.close()
        runCatching { recognizer?.close() }
        recognizer = null
        runCatching { model?.close() }
        model = null
    }

    private fun preparationFailure(
        stage: GuardianWakeFailureStage,
        failure: Throwable,
    ): GuardianWakeReadiness = synchronized(lock) {
        preparationFailureLocked(stage, failure)
    }

    private fun preparationFailureLocked(
        stage: GuardianWakeFailureStage,
        failure: Throwable,
    ): GuardianWakeReadiness {
        closeLocked()
        logWakeFailure(stage, failure)
        return GuardianWakeReadiness.MODEL_INVALID
    }

    private companion object {
        val SUPPORTED_ABIS = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        const val WAKE_GRAMMAR = "[\"小 友\", \"小 友 小 友\", \"[unk]\"]"
        const val MAXIMUM_WAKE_UTTERANCE_SECONDS = 5
        val lock = Any()
    }
}

/** 只接受一次或连续两次固定“小友”；其他自由文本、未知词及三次以上均拒绝。 */
internal fun guardianWakePhraseRepetitions(resultJson: String): Int {
    if (resultJson.length > MAX_WAKE_RESULT_CHARACTERS) return 0
    val root = runCatching { Json.parseToJsonElement(resultJson).jsonObject }.getOrNull()
        ?: return 0
    val text = root["text"]?.jsonPrimitive?.content
        ?: root["partial"]?.jsonPrimitive?.content
        ?: return 0
    return when (text.filterNot(Char::isWhitespace)) {
        GuardianWakeModelManifest.WAKE_PHRASE -> 1
        GuardianWakeModelManifest.WAKE_PHRASE.repeat(2) -> 2
        else -> 0
    }
}

private const val MAX_WAKE_RESULT_CHARACTERS = 1_024

private const val GUARDIAN_WAKE_LOG_TAG = "AiFriendGuardianWake"

/** 唤醒模型失败日志只暴露固定阶段和异常类型，不记录路径、错误原文或用户数据。 */
internal enum class GuardianWakeFailureStage {
    MODEL_INSTALL,
    NATIVE_BINDING,
    MODEL_LOAD,
    RECOGNIZER_INITIALIZE,
    DETECTION,
    RESET,
}

internal fun guardianWakeFailureLogLine(
    stage: GuardianWakeFailureStage,
    failure: Throwable,
): String = "event=guardian_wake_failure stage=${stage.name} " +
    "exception=${failure.javaClass.simpleName.ifBlank { "Unknown" }}"

private fun logWakeFailure(stage: GuardianWakeFailureStage, failure: Throwable) {
    Log.e(
        GUARDIAN_WAKE_LOG_TAG,
        guardianWakeFailureLogLine(stage, failure),
    )
}
