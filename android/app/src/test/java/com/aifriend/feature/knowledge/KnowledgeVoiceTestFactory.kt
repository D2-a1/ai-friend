package com.aifriend.feature.knowledge

import com.aifriend.core.audio.*
import com.aifriend.core.voice.OfflineSpeechPort
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** 真控制器、模拟音频与转写；绝不调用Android服务、真实语音或外部模型。 */
internal class KnowledgeVoiceTestFactory : KnowledgeVoiceFactory() {
    var unavailableReason: KnowledgeVoiceFailure? = null
    var speechGate: CompletableDeferred<Unit>? = null
    var cleanupGate: CompletableDeferred<Unit>? = null
    var cleanupFails = false
    val transcripts = ArrayDeque<String>()
    val spoken = mutableListOf<String>()
    var acquisitions = 0
    var releases = 0
    var retentions = 0
    var captures = 0
    var cancelled = 0
    val audio = mutableListOf<CapturedAudio>()
    override fun unavailable() = unavailableReason
    override fun create(scope: CoroutineScope, isCurrent: () -> Boolean,
        submit: (String) -> KnowledgeVoiceSubmission, cancel: () -> Unit) = KnowledgeVoiceFlow(
        scope,
        KnowledgeVoiceAudioGate {
            acquisitions++
            object : KnowledgeVoiceAudioLease {
                override suspend fun release() { releases++ }
                override suspend fun retainAfterCleanupFailure() { retentions++ }
            }
        },
        {
            object : AudioCapturePort {
                override val state = MutableStateFlow(AudioCaptureState.STOPPED)
                override suspend fun start(maxDurationMs: Int) { captures++; state.value = AudioCaptureState.CAPTURING }
                override suspend fun awaitSpeechEndpoint(): SpeechEndpointBoundary {
                    if (transcripts.isEmpty()) awaitCancellation()
                    return SpeechEndpointBoundary.UTTERANCE_COMPLETE
                }
                override suspend fun stop() = CapturedAudio(byteArrayOf(1, 2, 3), 100).also { audio += it }
                override suspend fun cancel() {
                    cancelled++; cleanupGate?.await()
                    if (cleanupFails) error("SYNTHETIC_CLEANUP")
                    state.value = AudioCaptureState.STOPPED
                }
            }
        },
        {
            object : OfflineSpeechPort {
                override suspend fun prepare() = true
                override suspend fun speak(text: String): Boolean { spoken += text; speechGate?.await(); return true }
                override fun close() = Unit
            }
        },
        object : QuestionSpeechRecognizer {
            override suspend fun recognize(audio: CapturedAudio): QuestionTranscript =
                try { QuestionTranscript(transcripts.removeFirst()) } finally { audio.clear() }
        }, isCurrent, submit, cancel,
    )
}
