package com.aifriend.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.aifriend.core.settings.UserSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 只使用设备已安装离线中文 voice 的进程内语音端口。 */
interface OfflineSpeechPort {
    suspend fun prepare(): Boolean
    suspend fun speak(text: String): Boolean
    fun close()
}

/**
 * 系统离线中文 TTS 适配器。
 *
 * 不触发语言包下载、不选择需要网络的 voice、不写语音文件；没有合规 voice 时失败关闭。
 * 本实现故意不使用单例作用域：守护服务、任务页等消费方会在各自生命周期调用
 * [close]，必须各自持有引擎，避免停止守护时关闭任务页刚开始的播报。
 */
class AndroidOfflineSpeechAdapter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: UserSettingsRepository,
) : OfflineSpeechPort {
    private val mutex = Mutex()
    private var engine: TextToSpeech? = null
    private var prepared = false
    private var pending: CancellableContinuation<Boolean>? = null
    private var lifecycleVersion = 0L

    override suspend fun prepare(): Boolean = mutex.withLock {
        val expectedVersion = synchronized(this) {
            if (prepared && engine != null) return@withLock true
            closeLocked()
            lifecycleVersion
        }
        val created = createEngine() ?: return@withLock false
        val offlineVoice = created.voices.orEmpty()
            .filter { voice ->
                !voice.isNetworkConnectionRequired &&
                    voice.locale.language == Locale.CHINESE.language
            }
            .sortedWith(
                compareByDescending<android.speech.tts.Voice> {
                    it.locale == Locale.SIMPLIFIED_CHINESE
                }.thenByDescending { it.quality }.thenBy { it.name },
            )
            .firstOrNull()
        if (offlineVoice == null || created.setVoice(offlineVoice) != TextToSpeech.SUCCESS) {
            created.shutdown()
            return@withLock false
        }
        created.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        created.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    completePending(utteranceId == UTTERANCE_ID)
                }

                @Deprecated("Android TTS legacy callback")
                override fun onError(utteranceId: String?) {
                    completePending(false)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    completePending(false)
                }
            },
        )
        synchronized(this) {
            if (lifecycleVersion != expectedVersion) {
                created.shutdown()
                false
            } else {
                engine = created
                prepared = true
                true
            }
        }
    }

    override suspend fun speak(text: String): Boolean {
        if (text.isBlank() || text.length > MAXIMUM_TEXT_LENGTH || !prepare()) return false
        val playbackSettings = runCatching {
            settingsRepository.current()
        }.getOrElse { return false }
        val speechParameters = Bundle().apply {
            putFloat(
                TextToSpeech.Engine.KEY_PARAM_VOLUME,
                playbackSettings.speechVolume.ttsVolume,
            )
        }
        return suspendCancellableCoroutine { continuation ->
            synchronized(this) {
                if (pending != null) {
                    continuation.resume(false)
                    return@synchronized
                }
                val activeEngine = engine
                if (activeEngine == null) {
                    continuation.resume(false)
                    return@synchronized
                }
                if (activeEngine.setSpeechRate(playbackSettings.speechRate.ttsRate) !=
                    TextToSpeech.SUCCESS
                ) {
                    continuation.resume(false)
                    return@synchronized
                }
                pending = continuation
                continuation.invokeOnCancellation {
                    synchronized(this) {
                        if (pending === continuation) pending = null
                    }
                    activeEngine.stop()
                }
                val result = activeEngine.speak(
                    text,
                    TextToSpeech.QUEUE_FLUSH,
                    speechParameters,
                    UTTERANCE_ID,
                )
                if (result != TextToSpeech.SUCCESS) {
                    pending = null
                    continuation.resume(false)
                }
            }
        }
    }

    override fun close() = synchronized(this) {
        lifecycleVersion++
        closeLocked()
    }

    private suspend fun createEngine(): TextToSpeech? = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            var created: TextToSpeech? = null
            created = TextToSpeech(context) { status ->
                val value = created
                if (status == TextToSpeech.SUCCESS && value != null) {
                    continuation.resume(value)
                } else {
                    value?.shutdown()
                    continuation.resume(null)
                }
            }
            continuation.invokeOnCancellation { created?.shutdown() }
        }
    }

    @Synchronized
    private fun completePending(success: Boolean) {
        val continuation = pending ?: return
        pending = null
        if (continuation.isActive) continuation.resume(success)
    }

    private fun closeLocked() {
        prepared = false
        pending?.let { continuation ->
            pending = null
            if (continuation.isActive) continuation.resume(false)
        }
        engine?.stop()
        engine?.shutdown()
        engine = null
    }

    private companion object {
        const val MAXIMUM_TEXT_LENGTH = 500
        const val UTTERANCE_ID = "offline-chinese-speech-v1"
    }
}
