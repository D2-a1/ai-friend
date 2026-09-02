package com.aifriend.feature.guardian.wake

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aifriend.core.audio.AudioCapturePort
import com.aifriend.core.audio.AudioPlaybackContent
import com.aifriend.core.audio.AudioPlaybackPort
import com.aifriend.core.audio.CapturedAudio
import com.aifriend.core.audio.VoiceTemplateRecordingNormalizer
import com.aifriend.core.audio.VoiceTemplateRecordingResult
import com.aifriend.core.audio.toAudioCaptureFailurePresentation
import com.aifriend.core.audio.toAudioPlaybackUserMessage
import com.aifriend.core.voice.LocalVoiceTemplateCandidate
import com.aifriend.core.voice.LocalVoiceTemplateCoordinator
import com.aifriend.core.voice.LocalVoiceTemplateException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 个人“小友”发音双录、本机一致性检查和 Keystore 密文保存编排器。 */
@HiltViewModel
class WakeWordEnrollmentViewModel @Inject constructor(
    private val audioCapturePort: AudioCapturePort,
    private val audioPlaybackPort: AudioPlaybackPort,
    private val recordingNormalizer: VoiceTemplateRecordingNormalizer,
    private val coordinator: LocalVoiceTemplateCoordinator,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(WakeWordEnrollmentUiState())
    val uiState: StateFlow<WakeWordEnrollmentUiState> = mutableUiState.asStateFlow()

    private var firstRecording: CapturedAudio? = null
    private var secondRecording: CapturedAudio? = null
    private var preparedCandidate: LocalVoiceTemplateCandidate? = null
    private var recordingTimeoutJob: Job? = null
    private var playbackJob: Job? = null
    private var savingJob: Job? = null
    private var sessionGeneration = 0L

    /** 打开页面并只检查当前 owner 是否已有本机模板。 */
    fun open() {
        val generation = ++sessionGeneration
        cancelJobs()
        mutableUiState.value = WakeWordEnrollmentUiState()
        viewModelScope.launch {
            clearAudioResources()
            val existing = runCatching { coordinator.loadWakeWord() }.getOrNull()
            val hasExisting = existing != null
            existing?.clear()
            if (generation == sessionGeneration) {
                mutableUiState.value = WakeWordEnrollmentUiState(
                    stage = WakeWordEnrollmentStage.READY_FIRST,
                    hasExistingTemplate = hasExisting,
                )
            }
        }
    }

    /** 开始第一遍或第二遍录音。 */
    fun startRecording() {
        val state = mutableUiState.value
        val target = when (state.stage) {
            WakeWordEnrollmentStage.READY_FIRST -> WakeWordEnrollmentStage.RECORDING_FIRST
            WakeWordEnrollmentStage.FIRST_RECORDED -> WakeWordEnrollmentStage.RECORDING_SECOND
            else -> return
        }
        val fallback = state.stage
        val generation = sessionGeneration
        mutableUiState.value = state.copy(
            stage = target,
            errorMessage = null,
            microphonePermissionRecoveryRequired = false,
            playingRecording = null,
        )
        viewModelScope.launch {
            try {
                audioPlaybackPort.stop()
                audioCapturePort.start(MAXIMUM_RECORDING_MS)
                ensureCurrent(generation)
                recordingTimeoutJob?.cancel()
                recordingTimeoutJob = viewModelScope.launch {
                    delay(MAXIMUM_RECORDING_MS.toLong())
                    finishRecording()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (generation == sessionGeneration) {
                    val failure = exception.toAudioCaptureFailurePresentation()
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = fallback,
                        microphonePermissionRecoveryRequired = failure.permissionRecoveryRequired,
                        errorMessage = failure.userMessage,
                    )
                }
            }
        }
    }

    /** 停止录音并裁掉首尾静音。 */
    fun finishRecording() {
        val state = mutableUiState.value
        val slot = when (state.stage) {
            WakeWordEnrollmentStage.RECORDING_FIRST -> WakeWordRecordingSlot.FIRST
            WakeWordEnrollmentStage.RECORDING_SECOND -> WakeWordRecordingSlot.SECOND
            else -> return
        }
        recordingTimeoutJob?.cancel()
        recordingTimeoutJob = null
        val checking = if (slot == WakeWordRecordingSlot.FIRST) {
            WakeWordEnrollmentStage.CHECKING_FIRST
        } else {
            WakeWordEnrollmentStage.CHECKING_SECOND
        }
        val fallback = if (slot == WakeWordRecordingSlot.FIRST) {
            WakeWordEnrollmentStage.READY_FIRST
        } else {
            WakeWordEnrollmentStage.FIRST_RECORDED
        }
        val generation = sessionGeneration
        mutableUiState.value = state.copy(stage = checking)
        viewModelScope.launch {
            var captured: CapturedAudio? = null
            try {
                captured = audioCapturePort.stop()
                ensureCurrent(generation)
                when (val result = recordingNormalizer.normalize(captured)) {
                    is VoiceTemplateRecordingResult.Passed -> {
                        captured.clear()
                        captured = null
                        saveRecording(slot, result)
                    }
                    is VoiceTemplateRecordingResult.Rejected -> {
                        captured.clear()
                        captured = null
                        mutableUiState.value = mutableUiState.value.copy(
                            stage = fallback,
                            errorMessage = result.message.replace("当前称呼或指令", "“小友”"),
                        )
                    }
                }
            } catch (exception: CancellationException) {
                captured?.clear()
                throw exception
            } catch (exception: Exception) {
                captured?.clear()
                if (generation == sessionGeneration) {
                    val failure = exception.toAudioCaptureFailurePresentation(
                        unknownMessage = "录音处理没有完成，请重新录制",
                    )
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = fallback,
                        microphonePermissionRecoveryRequired = failure.permissionRecoveryRequired,
                        errorMessage = failure.userMessage,
                    )
                }
            }
        }
    }

    /** 回放一遍当前内存录音。 */
    fun play(slot: WakeWordRecordingSlot) {
        if (mutableUiState.value.playingRecording != null ||
            mutableUiState.value.stage in NON_INTERACTIVE_STAGES
        ) return
        val audio = if (slot == WakeWordRecordingSlot.FIRST) firstRecording else secondRecording
        audio ?: return
        val generation = sessionGeneration
        playbackJob = viewModelScope.launch {
            mutableUiState.value = mutableUiState.value.copy(
                playingRecording = slot,
                errorMessage = null,
            )
            try {
                audioPlaybackPort.play(audio.wavBytes)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (generation == sessionGeneration) {
                    mutableUiState.value = mutableUiState.value.copy(
                        errorMessage = exception.toAudioPlaybackUserMessage(
                            AudioPlaybackContent.RECORDING,
                        ),
                    )
                }
            } finally {
                if (generation == sessionGeneration) {
                    mutableUiState.value = mutableUiState.value.copy(playingRecording = null)
                }
            }
        }
    }

    /** 只重录指定一遍；重录第一遍会同时作废第二遍。 */
    fun retake(slot: WakeWordRecordingSlot) {
        if (mutableUiState.value.stage in NON_INTERACTIVE_STAGES) return
        playbackJob?.cancel()
        viewModelScope.launch { audioPlaybackPort.stop() }
        preparedCandidate.clearAndForget()
        preparedCandidate = null
        when (slot) {
            WakeWordRecordingSlot.FIRST -> {
                firstRecording.clearAndForget()
                secondRecording.clearAndForget()
                firstRecording = null
                secondRecording = null
                mutableUiState.value = mutableUiState.value.copy(
                    stage = WakeWordEnrollmentStage.READY_FIRST,
                    firstDurationMs = null,
                    secondDurationMs = null,
                    playingRecording = null,
                    errorMessage = null,
                )
            }
            WakeWordRecordingSlot.SECOND -> {
                secondRecording.clearAndForget()
                secondRecording = null
                mutableUiState.value = mutableUiState.value.copy(
                    stage = WakeWordEnrollmentStage.FIRST_RECORDED,
                    secondDurationMs = null,
                    playingRecording = null,
                    errorMessage = null,
                )
            }
        }
    }

    /** 明确确认后只保存加密声学模板并清理两段原始录音。 */
    fun confirmSave() {
        val candidate = preparedCandidate ?: return
        if (mutableUiState.value.stage != WakeWordEnrollmentStage.REVIEW) return
        val generation = sessionGeneration
        mutableUiState.value = mutableUiState.value.copy(
            stage = WakeWordEnrollmentStage.SAVING,
            errorMessage = null,
            playingRecording = null,
        )
        savingJob = viewModelScope.launch {
            try {
                audioPlaybackPort.stop()
                coordinator.replaceWakeWord(candidate)
                ensureCurrent(generation)
                clearRecordedAudio()
                mutableUiState.value = mutableUiState.value.copy(
                    stage = WakeWordEnrollmentStage.COMPLETED,
                    firstDurationMs = null,
                    secondDurationMs = null,
                    hasExistingTemplate = true,
                    errorMessage = null,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                if (generation == sessionGeneration) {
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = WakeWordEnrollmentStage.REVIEW,
                        errorMessage = "“小友”没有保存，请稍后重试",
                    )
                }
            }
        }
    }

    fun onMicrophonePermissionDenied() {
        mutableUiState.value = mutableUiState.value.copy(
            microphonePermissionRecoveryRequired = true,
            errorMessage = "需要允许麦克风权限才能录制“小友”",
        )
    }

    fun dismissError() {
        mutableUiState.value = mutableUiState.value.copy(errorMessage = null)
    }

    fun recordAgain() {
        if (mutableUiState.value.stage == WakeWordEnrollmentStage.COMPLETED) {
            mutableUiState.value = mutableUiState.value.copy(
                stage = WakeWordEnrollmentStage.READY_FIRST,
                errorMessage = null,
            )
        }
    }

    fun leave() {
        ++sessionGeneration
        cancelJobs()
        viewModelScope.launch {
            clearAudioResources()
            mutableUiState.value = WakeWordEnrollmentUiState()
        }
    }

    override fun onCleared() {
        ++sessionGeneration
        cancelJobs()
        clearRecordedAudio()
        super.onCleared()
    }

    private fun saveRecording(
        slot: WakeWordRecordingSlot,
        result: VoiceTemplateRecordingResult.Passed,
    ) {
        val audio = result.audio
        if (slot == WakeWordRecordingSlot.FIRST) {
            preparedCandidate.clearAndForget()
            preparedCandidate = null
            firstRecording.clearAndForget()
            secondRecording.clearAndForget()
            firstRecording = audio
            secondRecording = null
            mutableUiState.value = mutableUiState.value.copy(
                stage = WakeWordEnrollmentStage.FIRST_RECORDED,
                firstDurationMs = result.durationMs,
                secondDurationMs = null,
                errorMessage = null,
            )
            return
        }
        secondRecording.clearAndForget()
        secondRecording = audio
        val first = firstRecording
        if (first == null) {
            audio.clear()
            secondRecording = null
            mutableUiState.value = mutableUiState.value.copy(
                stage = WakeWordEnrollmentStage.READY_FIRST,
                firstDurationMs = null,
                secondDurationMs = null,
                errorMessage = "第一遍录音已失效，请重新录制",
            )
            return
        }
        try {
            preparedCandidate = coordinator.prepare(first.wavBytes, audio.wavBytes)
            mutableUiState.value = mutableUiState.value.copy(
                stage = WakeWordEnrollmentStage.REVIEW,
                secondDurationMs = result.durationMs,
                errorMessage = null,
            )
        } catch (exception: Exception) {
            audio.clear()
            secondRecording = null
            preparedCandidate.clearAndForget()
            preparedCandidate = null
            mutableUiState.value = mutableUiState.value.copy(
                stage = WakeWordEnrollmentStage.FIRST_RECORDED,
                secondDurationMs = null,
                errorMessage = if (exception is LocalVoiceTemplateException) {
                    exception.message
                } else {
                    "两遍“小友”发音没有通过一致性检查，请只重录第二遍"
                },
            )
        }
    }

    private suspend fun clearAudioResources() {
        recordingTimeoutJob?.cancel()
        runCatching { audioCapturePort.cancel() }
        runCatching { audioPlaybackPort.stop() }
        clearRecordedAudio()
    }

    private fun clearRecordedAudio() {
        firstRecording.clearAndForget()
        secondRecording.clearAndForget()
        preparedCandidate.clearAndForget()
        firstRecording = null
        secondRecording = null
        preparedCandidate = null
    }

    private fun cancelJobs() {
        recordingTimeoutJob?.cancel()
        playbackJob?.cancel()
        savingJob?.cancel()
        recordingTimeoutJob = null
        playbackJob = null
        savingJob = null
    }

    private fun ensureCurrent(generation: Long) {
        if (generation != sessionGeneration) throw CancellationException("小友录制会话已作废")
    }

    private fun CapturedAudio?.clearAndForget() = this?.clear()
    private fun LocalVoiceTemplateCandidate?.clearAndForget() = this?.clear()

    private companion object {
        const val MAXIMUM_RECORDING_MS = 5_000
        val NON_INTERACTIVE_STAGES = setOf(
            WakeWordEnrollmentStage.IDLE,
            WakeWordEnrollmentStage.RECORDING_FIRST,
            WakeWordEnrollmentStage.CHECKING_FIRST,
            WakeWordEnrollmentStage.RECORDING_SECOND,
            WakeWordEnrollmentStage.CHECKING_SECOND,
            WakeWordEnrollmentStage.SAVING,
        )
    }
}
