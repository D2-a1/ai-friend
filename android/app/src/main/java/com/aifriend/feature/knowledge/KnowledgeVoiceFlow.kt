package com.aifriend.feature.knowledge

import com.aifriend.core.audio.*
import com.aifriend.core.voice.OfflineSpeechPort
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 实际接线必须先暂停对应守护并等待资源释放；每次获取独立所有者，不能借用微信执行令牌。 */
internal fun interface KnowledgeVoiceAudioGate { suspend fun acquire(): KnowledgeVoiceAudioLease? }
internal fun interface KnowledgeVoiceAudioLease {
    suspend fun release()
    /** 清理结果未知时保留阻挡；不得恢复守护或假称麦克风空闲。 */
    suspend fun retainAfterCleanupFailure() = Unit
}

enum class KnowledgeVoicePhase { IDLE, ACQUIRING, SPEAKING, LISTENING, TRANSCRIBING, WAITING_RESULT, AWAITING_CONTINUE, ERROR, CLOSED }
enum class KnowledgeVoiceFailure(val message: String) {
    AUDIO_BUSY("麦克风资源暂时无法交接，可以先输入文字"),
    MICROPHONE_DENIED("麦克风权限未开启，可以先输入文字"),
    AUDIO_INTERRUPTED("录音已中断，请准备好后重新开始"),
    ASR_UNAVAILABLE("本机识别暂时不可用，可以先输入文字"),
    PLAYBACK_FAILED("暂时不能完整播报，请查看文字或重试"),
    CLEANUP_FAILED("音频资源释放尚未确认，请暂勿重新录音"),
}
data class KnowledgeVoiceState(
    val phase: KnowledgeVoicePhase = KnowledgeVoicePhase.IDLE,
    val failure: KnowledgeVoiceFailure? = null,
    val retries: Int = 0,
)
internal sealed interface KnowledgeVoiceSubmission {
    data object Submitted : KnowledgeVoiceSubmission
    /** 固定本机提示，不接受模型返回的指令或个人原句作提示。 */
    data object UnsupportedGraphQuestion : KnowledgeVoiceSubmission
    data object Rejected : KnowledgeVoiceSubmission
}

/**
 * 页面作用域、UI调度器调用。只管理问答音频，不拥有联系动作端口。
 * 每段播报新建独立TTS，避免旧引擎迟到完成回调放行新一轮录音；没有自动恢复或音频上传。
 */
internal class KnowledgeVoiceFlow(
    private val scope: CoroutineScope,
    private val gate: KnowledgeVoiceAudioGate,
    private val newCapture: () -> AudioCapturePort,
    private val newSpeech: () -> OfflineSpeechPort,
    private val recognizer: QuestionSpeechRecognizer,
    private val isCurrent: () -> Boolean,
    private val submit: (String) -> KnowledgeVoiceSubmission,
    private val cancelQuestion: () -> Unit,
) {
    private val mutableState = MutableStateFlow(KnowledgeVoiceState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var generation = 0L
    private var closed = false
    private var cleanupFailed = false
    private var activeSpeech: OfflineSpeechPort? = null

    fun listen() {
        if (closed || cleanupFailed || operation?.isActive == true || !isCurrent() || state.value.phase == KnowledgeVoicePhase.WAITING_RESULT) return
        runOperation { token -> listenLoop(token, "请说您的问题。") }
    }

    /** 仅页面交付当前已验证回答；网络未知或无效回答不调用此入口。 */
    fun present(text: String, continueListening: Boolean) {
        if (closed || cleanupFailed || !isCurrent() || state.value.phase != KnowledgeVoicePhase.WAITING_RESULT) return
        runOperation { token ->
            if (text.isBlank() || text.codePointCount(0, text.length) > 3000) throw VoiceFailure(KnowledgeVoiceFailure.PLAYBACK_FAILED)
            speak(text, token)
            if (continueListening) listenLoop(token, "您可以继续提问，也可以说取消问答。")
            else mutableState.value = KnowledgeVoiceState(KnowledgeVoicePhase.AWAITING_CONTINUE)
        }
    }

    /** 服务端失败/结果未知只暂停，不将其解释成没听清或重发原问题。 */
    fun pauseForResultFailure() {
        if (closed || state.value.phase != KnowledgeVoicePhase.WAITING_RESULT) return
        generation++; operation?.cancel()
        mutableState.value = KnowledgeVoiceState(KnowledgeVoicePhase.AWAITING_CONTINUE)
    }

    fun stop() {
        if (closed) return
        closed = true; generation++; operation?.cancel()
        try { activeSpeech?.let { closeSpeech(it) } } catch (_: VoiceFailure) { }
        mutableState.value = KnowledgeVoiceState(KnowledgeVoicePhase.CLOSED, if (cleanupFailed) KnowledgeVoiceFailure.CLEANUP_FAILED else null)
    }

    suspend fun shutdown() {
        stop()
        withContext(NonCancellable) { operation?.join() }
    }

    private fun runOperation(block: suspend (Long) -> Unit) {
        val previous = operation
        previous?.cancel()
        val token = ++generation
        operation = scope.launch {
            previous?.join()
            if (!alive(token) || cleanupFailed) return@launch
            var lease: KnowledgeVoiceAudioLease? = null
            try {
                mutableState.value = KnowledgeVoiceState(KnowledgeVoicePhase.ACQUIRING)
                // acquire实现须取消安全；租约在NonCancellable内交付，迟到交付也由本finally释放。
                withContext(NonCancellable) { lease = gate.acquire() }
                checkAlive(token)
                if (lease == null) throw VoiceFailure(KnowledgeVoiceFailure.AUDIO_BUSY)
                block(token)
            } catch (e: CancellationException) { throw e }
                catch (e: VoiceFailure) { if (alive(token)) mutableState.value = state.value.copy(phase = KnowledgeVoicePhase.ERROR, failure = e.reason) }
                catch (_: Exception) { if (alive(token)) mutableState.value = state.value.copy(phase = KnowledgeVoicePhase.ERROR, failure = KnowledgeVoiceFailure.AUDIO_INTERRUPTED) }
                catch (_: LinkageError) { if (alive(token)) mutableState.value = state.value.copy(phase = KnowledgeVoicePhase.ERROR, failure = KnowledgeVoiceFailure.AUDIO_INTERRUPTED) }
            finally {
                withContext(NonCancellable) {
                    try {
                        if (cleanupFailed) lease?.retainAfterCleanupFailure() else lease?.release()
                    }
                    catch (_: Exception) { reportCleanupFailure() }
                    catch (_: LinkageError) { reportCleanupFailure() }
                }
            }
        }
    }

    private suspend fun listenLoop(token: Long, initialPrompt: String) {
        var prompt = initialPrompt
        var retries = 0
        while (true) {
            checkAlive(token)
            speak(prompt, token)
            try {
                val transcript = recordAndRecognize(token)
                checkAlive(token)
                when (transcript.trim().trimEnd('。', '！', '.', '!')) {
                    "取消", "取消问答", "结束问答" -> { cancelQuestion(); stop(); return }
                    "重听", "重新说", "重新说一遍", "不是这个问题" -> { prompt = "请重新说您的问题。" }
                    else -> {
                        mutableState.value = KnowledgeVoiceState(KnowledgeVoicePhase.WAITING_RESULT)
                        when (submit(transcript)) {
                            KnowledgeVoiceSubmission.Submitted -> return
                            KnowledgeVoiceSubmission.Rejected -> { mutableState.value = KnowledgeVoiceState(KnowledgeVoicePhase.AWAITING_CONTINUE); return }
                            KnowledgeVoiceSubmission.UnsupportedGraphQuestion -> prompt = KnowledgeGraphSpeechParser.HELP
                        }
                    }
                }
            } catch (e: QuestionSpeechException) {
                if (e.failure !in setOf(QuestionSpeechFailure.NO_SPEECH, QuestionSpeechFailure.TOO_LONG))
                    throw VoiceFailure(KnowledgeVoiceFailure.ASR_UNAVAILABLE)
                prompt = if (e.failure == QuestionSpeechFailure.TOO_LONG) "这次话语过长，请简短地重新说。" else "这次没有听到有效问题，请重新说。"
            }
            if (retries == 3) {
                mutableState.value = KnowledgeVoiceState(KnowledgeVoicePhase.AWAITING_CONTINUE, retries = retries)
                return
            }
            retries++
            mutableState.value = state.value.copy(retries = retries)
        }
    }

    private suspend fun recordAndRecognize(token: Long): String {
        val capture = newCapture()
        var audio: CapturedAudio? = null
        try {
            mutableState.value = state.value.copy(phase = KnowledgeVoicePhase.LISTENING)
            try {
                capture.startUtterance(30_000)
                checkAlive(token)
                val endpoint = withTimeoutOrNull(35_000) { capture.awaitSpeechEndpoint() }
                checkAlive(token)
                when (endpoint) {
                    SpeechEndpointBoundary.UTTERANCE_COMPLETE -> audio = capture.stop()
                    SpeechEndpointBoundary.NO_SPEECH_TIMEOUT -> throw QuestionSpeechException(QuestionSpeechFailure.NO_SPEECH)
                    SpeechEndpointBoundary.MAXIMUM_REACHED -> throw QuestionSpeechException(QuestionSpeechFailure.TOO_LONG)
                    else -> throw VoiceFailure(KnowledgeVoiceFailure.AUDIO_INTERRUPTED)
                }
            } catch (e: AudioCaptureException) {
                throw VoiceFailure(if (e.failure == AudioCaptureFailure.PERMISSION_DENIED) KnowledgeVoiceFailure.MICROPHONE_DENIED else KnowledgeVoiceFailure.AUDIO_INTERRUPTED)
            } finally {
                withContext(NonCancellable) {
                    try { capture.cancel() }
                    catch (_: Exception) { reportCleanupFailure(); throw VoiceFailure(KnowledgeVoiceFailure.CLEANUP_FAILED) }
                    catch (_: LinkageError) { reportCleanupFailure(); throw VoiceFailure(KnowledgeVoiceFailure.CLEANUP_FAILED) }
                }
            }
            checkAlive(token)
            mutableState.value = state.value.copy(phase = KnowledgeVoicePhase.TRANSCRIBING)
            return recognizer.recognize(audio!!).text.also { checkAlive(token) }
        } finally { audio?.clear() }
    }

    private suspend fun speak(text: String, token: Long) {
        // 系统TTS端口限制500个UTF-16字符；按码点切分，绝不切断代理对。
        var position = 0
        while (position < text.length) {
            checkAlive(token)
            var end = minOf(position + 500, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            val speech = newSpeech(); activeSpeech = speech
            mutableState.value = state.value.copy(phase = KnowledgeVoicePhase.SPEAKING)
            try {
                val played = withTimeoutOrNull(180_000) { speech.speak(text.substring(position, end)) } == true
                checkAlive(token)
                if (!played) throw VoiceFailure(KnowledgeVoiceFailure.PLAYBACK_FAILED)
            } finally { closeSpeech(speech) }
            position = end
        }
    }

    private fun closeSpeech(speech: OfflineSpeechPort) {
        try { speech.close() }
        catch (_: Exception) { reportCleanupFailure(); throw VoiceFailure(KnowledgeVoiceFailure.CLEANUP_FAILED) }
        catch (_: LinkageError) { reportCleanupFailure(); throw VoiceFailure(KnowledgeVoiceFailure.CLEANUP_FAILED) }
        finally { if (activeSpeech === speech) activeSpeech = null }
    }

    private fun reportCleanupFailure() {
        cleanupFailed = true
        // 清理失败影响整个音频持有者，不是可丢弃的旧回答。新代次仍须看到失败且不能再开麦。
        mutableState.value = state.value.copy(
            phase = if (closed) KnowledgeVoicePhase.CLOSED else KnowledgeVoicePhase.ERROR,
            failure = KnowledgeVoiceFailure.CLEANUP_FAILED,
        )
    }

    private fun alive(token: Long) = !closed && generation == token && isCurrent()
    private suspend fun checkAlive(token: Long) { currentCoroutineContext().ensureActive(); if (!alive(token)) throw CancellationException("QUESTION_VOICE_STALE") }
    private class VoiceFailure(val reason: KnowledgeVoiceFailure) : RuntimeException(reason.name)
}
