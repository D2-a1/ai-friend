package com.aifriend.feature.knowledge

import com.aifriend.core.audio.*
import com.aifriend.core.voice.OfflineSpeechPort
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeVoiceFlowTest {
    @Test fun promptCompletesBeforeCaptureAndSubmitsOnlyOneTranscript() = runTest {
        val f = Fixture(this); f.speechGate = CompletableDeferred()
        f.flow.listen(); runCurrent(); assertTrue(f.captures.isEmpty()); assertEquals(KnowledgeVoicePhase.SPEAKING, f.flow.state.value.phase)
        f.speechGate!!.complete(Unit); runCurrent()
        assertEquals(listOf("合成知识问题"), f.submitted); assertEquals(KnowledgeVoicePhase.WAITING_RESULT, f.flow.state.value.phase)
        assertTrue(f.captures.single().cancelled); assertTrue(f.captures.single().audio.wavBytes.all { it == 0.toByte() })
        assertEquals(1, f.releases); f.flow.listen(); runCurrent(); assertEquals(1, f.captures.size)
        f.flow.shutdown()
    }
    @Test fun duplicateListenDoesNotCreateAnotherRecording() = runTest {
        val f = Fixture(this); f.endpointGate = CompletableDeferred(); f.flow.listen(); f.flow.listen(); runCurrent()
        assertEquals(1, f.captures.size); f.flow.stop(); runCurrent(); f.flow.shutdown(); assertEquals(1, f.releases)
    }
    @Test fun noSpeechAllowsExactlyThreeAutomaticRelistensThenExplicitContinue() = runTest {
        val f = Fixture(this); f.endpoint = SpeechEndpointBoundary.NO_SPEECH_TIMEOUT
        f.flow.listen(); runCurrent(); assertEquals(4, f.captures.size); assertEquals(3, f.flow.state.value.retries)
        assertEquals(KnowledgeVoicePhase.AWAITING_CONTINUE, f.flow.state.value.phase); assertTrue(f.submitted.isEmpty())
        f.endpoint = SpeechEndpointBoundary.UTTERANCE_COMPLETE; f.flow.listen(); runCurrent(); assertEquals(5, f.captures.size)
        assertEquals(1, f.submitted.size); f.flow.shutdown()
    }
    @Test fun unsupportedPrivateQuestionRelistensWithoutNetworkSubmission() = runTest {
        val f = Fixture(this); f.outcome = KnowledgeVoiceSubmission.UnsupportedGraphQuestion
        f.flow.listen(); runCurrent(); assertEquals(4, f.captures.size)
        assertEquals(KnowledgeVoicePhase.AWAITING_CONTINUE, f.flow.state.value.phase)
        assertTrue(f.spoken.contains(KnowledgeGraphSpeechParser.HELP)); f.flow.shutdown()
    }
    @Test fun playbackFailureNeverStartsMicrophone() = runTest {
        val f = Fixture(this); f.played = false; f.flow.listen(); runCurrent()
        assertEquals(KnowledgeVoiceFailure.PLAYBACK_FAILED, f.flow.state.value.failure); assertTrue(f.captures.isEmpty())
        assertEquals(1, f.releases); f.flow.shutdown()
    }
    @Test fun missingTtsCallbackTimesOutWithoutOpeningMicrophone() = runTest {
        val f = Fixture(this); f.speechGate = CompletableDeferred(); f.flow.listen(); advanceUntilIdle()
        assertEquals(KnowledgeVoiceFailure.PLAYBACK_FAILED, f.flow.state.value.failure); assertTrue(f.captures.isEmpty())
        assertTrue(f.speeches.all { it.closed }); f.flow.shutdown()
    }
    @Test fun leaveDiscardsLateSpeechCompletionAndReleasesOnlyOwnGate() = runTest {
        val f = Fixture(this); f.speechGate = CompletableDeferred(); f.ignoreSpeechCancellation = true
        f.flow.listen(); runCurrent(); f.flow.stop(); f.speechGate!!.complete(Unit); runCurrent(); f.flow.shutdown()
        assertTrue(f.captures.isEmpty()); assertTrue(f.submitted.isEmpty()); assertEquals(1, f.releases)
        assertEquals(KnowledgeVoicePhase.CLOSED, f.flow.state.value.phase)
    }
    @Test fun identityChangesDuringAsrSuppressSubmitAndClearAudio() = runTest {
        val f = Fixture(this); f.duringRecognition = { f.current = false }
        f.flow.listen(); runCurrent(); assertTrue(f.submitted.isEmpty())
        assertTrue(f.captures.single().audio.wavBytes.all { it == 0.toByte() }); f.flow.shutdown()
    }
    @Test fun asrUnavailableDoesNotRepeatedlyAskUserToSpeak() = runTest {
        val f = Fixture(this); f.recognitionFailure = QuestionSpeechFailure.UNAVAILABLE; f.flow.listen(); runCurrent()
        assertEquals(KnowledgeVoiceFailure.ASR_UNAVAILABLE, f.flow.state.value.failure); assertEquals(1, f.captures.size)
        assertTrue(f.submitted.isEmpty()); f.flow.shutdown()
    }
    @Test fun permissionDenialIsNotNoSpeech() = runTest {
        val f = Fixture(this); f.permissionDenied = true; f.flow.listen(); runCurrent()
        assertEquals(KnowledgeVoiceFailure.MICROPHONE_DENIED, f.flow.state.value.failure); assertEquals(1, f.captures.size); f.flow.shutdown()
    }
    @Test fun gateFailureDoesNotStartTtsOrCapture() = runTest {
        val f = Fixture(this); f.gateAvailable = false; f.flow.listen(); runCurrent()
        assertEquals(KnowledgeVoiceFailure.AUDIO_BUSY, f.flow.state.value.failure); assertTrue(f.speeches.isEmpty()); assertTrue(f.captures.isEmpty()); f.flow.shutdown()
    }
    @Test fun cleanupFailurePreventsNewRecordingOnSameFlow() = runTest {
        val f = Fixture(this); f.releaseFails = true; f.flow.listen(); runCurrent()
        assertEquals(KnowledgeVoiceFailure.CLEANUP_FAILED, f.flow.state.value.failure)
        f.flow.listen(); runCurrent(); assertEquals(1, f.captures.size); f.flow.shutdown()
    }
    @Test fun cancelCommandCancelsQuestionNotContactAction() = runTest {
        val f = Fixture(this); f.transcript = "取消问答。"; f.flow.listen(); runCurrent()
        assertEquals(1, f.questionCancels); assertTrue(f.submitted.isEmpty()); assertEquals(KnowledgeVoicePhase.CLOSED, f.flow.state.value.phase); f.flow.shutdown()
    }
    @Test fun lateReleaseFailureOverridesWaitingAnswerButNeverStartsNextPlayback() = runTest {
        val f = Fixture(this); f.releaseGate = CompletableDeferred(); f.releaseFails = true
        f.flow.listen(); runCurrent(); assertEquals(KnowledgeVoicePhase.WAITING_RESULT, f.flow.state.value.phase)
        f.flow.present("合成回答", true); runCurrent(); f.releaseGate!!.complete(Unit); runCurrent()
        assertEquals(KnowledgeVoicePhase.ERROR, f.flow.state.value.phase)
        assertEquals(KnowledgeVoiceFailure.CLEANUP_FAILED, f.flow.state.value.failure)
        assertEquals(1, f.spoken.size); assertEquals(1, f.captures.size); f.flow.shutdown()
    }
    @Test fun captureCleanupFailureDiscardsAudioAndNeverTranscribesOrRetries() = runTest {
        val f = Fixture(this); f.captureCleanupFails = true
        f.duringRecognition = { fail("Unreleased capture cannot be transcribed") }
        f.flow.listen(); runCurrent()
        assertEquals(KnowledgeVoiceFailure.CLEANUP_FAILED, f.flow.state.value.failure)
        assertTrue(f.captures.single().audio.wavBytes.all { it == 0.toByte() }); assertTrue(f.submitted.isEmpty())
        f.flow.listen(); runCurrent(); assertEquals(1, f.captures.size); f.flow.shutdown()
    }
    @Test fun cleanupFailureAfterLeaveIsStillObservableToPageCleanup() = runTest {
        val f = Fixture(this); f.captureCleanupFails = true; f.endpointGate = CompletableDeferred()
        f.flow.listen(); runCurrent(); f.flow.stop(); runCurrent(); f.flow.shutdown()
        assertEquals(KnowledgeVoicePhase.CLOSED, f.flow.state.value.phase)
        assertEquals(KnowledgeVoiceFailure.CLEANUP_FAILED, f.flow.state.value.failure); assertTrue(f.submitted.isEmpty())
    }
    @Test fun unconfirmedCaptureCleanupMustNotRestoreGuardianMicrophone() = runTest {
        val f = Fixture(this); f.captureCleanupFails = true
        f.flow.listen(); runCurrent(); f.flow.shutdown()
        assertEquals("Unknown recording cleanup must retain the guardian barrier", 0, f.releases)
    }
    @Test fun longAnswerUsesSeparateTtsInstancesWithoutSplittingSurrogates() = runTest {
        val f = Fixture(this); f.flow.listen(); runCurrent()
        val answer = "a".repeat(499) + "😀" + "b".repeat(700)
        val previous = f.spoken.size; f.flow.present(answer, false); runCurrent()
        val chunks = f.spoken.drop(previous)
        assertEquals(answer, chunks.joinToString("")); assertTrue(chunks.all { it.length <= 500 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
        assertTrue(f.speeches.all { it.closed }); assertEquals(KnowledgeVoicePhase.AWAITING_CONTINUE, f.flow.state.value.phase); f.flow.shutdown()
    }
    @Test fun answerPlaybackFinishesBeforeNextListeningAndFailureDoesNotResubmit() = runTest {
        val f = Fixture(this); f.flow.listen(); runCurrent(); f.speechGate = CompletableDeferred()
        f.flow.present("合成回答", true); runCurrent(); assertEquals(1, f.captures.size)
        f.speechGate!!.complete(Unit); runCurrent(); assertEquals(2, f.captures.size)
        f.flow.pauseForResultFailure(); runCurrent(); assertEquals(2, f.submitted.size)
        assertEquals(KnowledgeVoicePhase.AWAITING_CONTINUE, f.flow.state.value.phase); f.flow.shutdown()
    }

    private class Fixture(scope: CoroutineScope) {
        var current = true; var played = true; var gateAvailable = true; var releaseFails = false; var captureCleanupFails = false
        var permissionDenied = false; var ignoreSpeechCancellation = false
        var speechGate: CompletableDeferred<Unit>? = null; var endpointGate: CompletableDeferred<Unit>? = null
        var releaseGate: CompletableDeferred<Unit>? = null
        var endpoint = SpeechEndpointBoundary.UTTERANCE_COMPLETE; var transcript = "合成知识问题"
        var recognitionFailure: QuestionSpeechFailure? = null; var duringRecognition: () -> Unit = {}
        var releases = 0; var questionCancels = 0
        var outcome: KnowledgeVoiceSubmission = KnowledgeVoiceSubmission.Submitted
        val submitted = mutableListOf<String>(); val spoken = mutableListOf<String>()
        val captures = mutableListOf<Capture>(); val speeches = mutableListOf<Speech>()
        val flow = KnowledgeVoiceFlow(scope,
            KnowledgeVoiceAudioGate { if (!gateAvailable) null else KnowledgeVoiceAudioLease { releases++; releaseGate?.await(); if (releaseFails) error("Synthetic release failure") } },
            { Capture().also { captures += it } }, { Speech().also { speeches += it } },
            object : QuestionSpeechRecognizer {
                override suspend fun recognize(audio: CapturedAudio): QuestionTranscript {
                    duringRecognition(); recognitionFailure?.let { throw QuestionSpeechException(it) }; return QuestionTranscript(transcript)
                }
            }, { current }, { submitted += it; outcome }, { questionCancels++ },
        )
        inner class Capture : AudioCapturePort {
            override val state = MutableStateFlow(AudioCaptureState.STOPPED)
            val audio = CapturedAudio(byteArrayOf(1, 2, 3), 1000); var cancelled = false
            override suspend fun start(maxDurationMs: Int) {
                assertTrue(speeches.all { it.closed }); assertEquals(30_000, maxDurationMs)
                if (permissionDenied) throw AudioCaptureException(AudioCaptureFailure.PERMISSION_DENIED, "Synthetic")
                state.value = AudioCaptureState.CAPTURING
            }
            override suspend fun awaitSpeechEndpoint(): SpeechEndpointBoundary { endpointGate?.await(); return endpoint }
            override suspend fun stop() = audio
            override suspend fun cancel() { cancelled = true; state.value = AudioCaptureState.STOPPED; if (captureCleanupFails) error("Synthetic capture cleanup failure") }
        }
        inner class Speech : OfflineSpeechPort {
            var closed = false
            override suspend fun prepare() = true
            override suspend fun speak(text: String): Boolean {
                spoken += text
                if (ignoreSpeechCancellation) withContext(NonCancellable) { speechGate?.await() } else speechGate?.await()
                return played
            }
            override fun close() { closed = true }
        }
    }
}
