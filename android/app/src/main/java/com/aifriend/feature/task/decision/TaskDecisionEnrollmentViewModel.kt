package com.aifriend.feature.task.decision

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aifriend.core.audio.AudioCapturePort
import com.aifriend.core.audio.CapturedAudio
import com.aifriend.core.audio.VoiceTemplateRecordingNormalizer
import com.aifriend.core.audio.VoiceTemplateRecordingResult
import com.aifriend.core.audio.toAudioCaptureFailurePresentation
import com.aifriend.core.voice.LocalVoiceTemplateCandidate
import com.aifriend.core.voice.LocalVoiceTemplateCoordinator
import com.aifriend.core.voice.LocalVoiceTemplateException
import com.aifriend.core.voice.TaskDecisionTemplateType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** “确认/否认”两类个人短词双录、区分性检查与本机加密保存。 */
@HiltViewModel
class TaskDecisionEnrollmentViewModel @Inject constructor(
    private val audioCapturePort: AudioCapturePort,
    private val normalizer: VoiceTemplateRecordingNormalizer,
    private val coordinator: LocalVoiceTemplateCoordinator,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(TaskDecisionEnrollmentUiState())
    val uiState: StateFlow<TaskDecisionEnrollmentUiState> = mutableUiState.asStateFlow()

    private var firstRecording: CapturedAudio? = null
    private val candidates =
        linkedMapOf<TaskDecisionTemplateType, LocalVoiceTemplateCandidate>()
    private var timeoutJob: Job? = null
    private var generation = 0L

    fun open() {
        val current = ++generation
        timeoutJob?.cancel()
        clearMemory()
        mutableUiState.value = TaskDecisionEnrollmentUiState()
        viewModelScope.launch {
            runCatching { audioCapturePort.cancel() }
            val existing = runCatching { coordinator.loadTaskDecisionTemplates() }
                .getOrDefault(emptyList())
            val complete = existing.map { it.type }.toSet() ==
                TaskDecisionTemplateType.entries.toSet()
            existing.forEach { it.clear() }
            if (current == generation) {
                mutableUiState.value = TaskDecisionEnrollmentUiState(
                    stage = TaskDecisionEnrollmentStage.READY,
                    hasExistingTemplates = complete,
                )
            }
        }
    }

    fun startRecording() {
        val state = mutableUiState.value
        if (state.stage != TaskDecisionEnrollmentStage.READY) return
        val current = generation
        mutableUiState.value = state.copy(
            stage = TaskDecisionEnrollmentStage.RECORDING,
            errorMessage = null,
            microphonePermissionRecoveryRequired = false,
        )
        viewModelScope.launch {
            try {
                audioCapturePort.start(MAXIMUM_RECORDING_MS)
                ensureCurrent(current)
                timeoutJob?.cancel()
                timeoutJob = viewModelScope.launch {
                    delay(MAXIMUM_RECORDING_MS.toLong())
                    finishRecording()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (current == generation) {
                    val failure = exception.toAudioCaptureFailurePresentation()
                    mutableUiState.value = state.copy(
                        stage = TaskDecisionEnrollmentStage.READY,
                        errorMessage = failure.userMessage,
                        microphonePermissionRecoveryRequired =
                            failure.permissionRecoveryRequired,
                    )
                }
            }
        }
    }

    fun finishRecording() {
        val state = mutableUiState.value
        if (state.stage != TaskDecisionEnrollmentStage.RECORDING) return
        timeoutJob?.cancel()
        timeoutJob = null
        val current = generation
        mutableUiState.value = state.copy(stage = TaskDecisionEnrollmentStage.CHECKING)
        viewModelScope.launch {
            var captured: CapturedAudio? = null
            try {
                captured = audioCapturePort.stop()
                ensureCurrent(current)
                when (val result = normalizer.normalize(captured)) {
                    is VoiceTemplateRecordingResult.Rejected -> {
                        captured.clear()
                        captured = null
                        mutableUiState.value = state.copy(
                            stage = TaskDecisionEnrollmentStage.READY,
                            errorMessage = result.message.replace(
                                "当前称呼或指令",
                                "当前确认词",
                            ),
                        )
                    }
                    is VoiceTemplateRecordingResult.Passed -> {
                        captured.clear()
                        captured = null
                        accept(state, result.audio)
                    }
                }
            } catch (exception: CancellationException) {
                captured?.clear()
                throw exception
            } catch (exception: Exception) {
                captured?.clear()
                if (current == generation) {
                    val failure = exception.toAudioCaptureFailurePresentation(
                        unknownMessage = "录音处理没有完成，请重新录制",
                    )
                    mutableUiState.value = state.copy(
                        stage = TaskDecisionEnrollmentStage.READY,
                        errorMessage = failure.userMessage,
                        microphonePermissionRecoveryRequired =
                            failure.permissionRecoveryRequired,
                    )
                }
            }
        }
    }

    private fun accept(state: TaskDecisionEnrollmentUiState, audio: CapturedAudio) {
        if (state.currentTake == 1) {
            firstRecording?.clear()
            firstRecording = audio
            mutableUiState.value = state.copy(
                stage = TaskDecisionEnrollmentStage.READY,
                currentTake = 2,
                errorMessage = null,
            )
            return
        }
        val first = firstRecording
        if (first == null) {
            audio.clear()
            mutableUiState.value = state.copy(
                stage = TaskDecisionEnrollmentStage.READY,
                currentTake = 1,
                errorMessage = "第一遍录音已失效，请重新录制",
            )
            return
        }
        try {
            val candidate = coordinator.prepare(first.wavBytes, audio.wavBytes)
            if (!coordinator.isMutuallyDistinct(candidate, candidates.values)) {
                candidate.clear()
                throw LocalVoiceTemplateException(
                    "“确认”和“否认”的发音太相近，请重新录制当前词",
                )
            }
            candidates.remove(state.currentType)?.clear()
            candidates[state.currentType] = candidate
            first.clear()
            audio.clear()
            firstRecording = null
            val completed = candidates.keys.toSet()
            val next = TaskDecisionTemplateType.entries.firstOrNull { it !in completed }
            mutableUiState.value = if (next == null) {
                state.copy(
                    stage = TaskDecisionEnrollmentStage.READY_TO_SAVE,
                    currentTake = 1,
                    completedTypes = completed,
                    errorMessage = null,
                )
            } else {
                state.copy(
                    stage = TaskDecisionEnrollmentStage.READY,
                    currentType = next,
                    currentTake = 1,
                    completedTypes = completed,
                    errorMessage = null,
                )
            }
        } catch (exception: Exception) {
            audio.clear()
            mutableUiState.value = state.copy(
                stage = TaskDecisionEnrollmentStage.READY,
                currentTake = 2,
                errorMessage = if (exception is LocalVoiceTemplateException) {
                    exception.message
                } else {
                    "两遍发音没有通过检查，请只重录第二遍"
                },
            )
        }
    }

    fun confirmSave() {
        val state = mutableUiState.value
        if (state.stage != TaskDecisionEnrollmentStage.READY_TO_SAVE) return
        val current = generation
        mutableUiState.value = state.copy(
            stage = TaskDecisionEnrollmentStage.SAVING,
            errorMessage = null,
        )
        viewModelScope.launch {
            try {
                coordinator.replaceTaskDecisionTemplates(candidates.toMap())
                ensureCurrent(current)
                clearMemory()
                mutableUiState.value = state.copy(
                    stage = TaskDecisionEnrollmentStage.COMPLETED,
                    hasExistingTemplates = true,
                    errorMessage = null,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (current == generation) {
                    mutableUiState.value = state.copy(
                        stage = TaskDecisionEnrollmentStage.READY_TO_SAVE,
                        errorMessage = exception.message
                            ?: "确认词没有保存，请稍后重试",
                    )
                }
            }
        }
    }

    fun restart() {
        if (mutableUiState.value.stage != TaskDecisionEnrollmentStage.COMPLETED) return
        clearMemory()
        mutableUiState.value = TaskDecisionEnrollmentUiState(
            stage = TaskDecisionEnrollmentStage.READY,
            hasExistingTemplates = true,
        )
    }

    fun onMicrophonePermissionDenied() {
        mutableUiState.value = mutableUiState.value.copy(
            microphonePermissionRecoveryRequired = true,
            errorMessage = "需要允许麦克风权限才能录制确认词",
        )
    }

    fun dismissError() {
        mutableUiState.value = mutableUiState.value.copy(errorMessage = null)
    }

    fun leave() {
        ++generation
        timeoutJob?.cancel()
        viewModelScope.launch { runCatching { audioCapturePort.cancel() } }
        clearMemory()
        mutableUiState.value = TaskDecisionEnrollmentUiState()
    }

    override fun onCleared() {
        ++generation
        timeoutJob?.cancel()
        clearMemory()
        super.onCleared()
    }

    private fun clearMemory() {
        firstRecording?.clear()
        firstRecording = null
        candidates.values.forEach(LocalVoiceTemplateCandidate::clear)
        candidates.clear()
    }

    private fun ensureCurrent(current: Long) {
        if (current != generation) throw CancellationException("确认词录制会话已作废")
    }

    private companion object {
        const val MAXIMUM_RECORDING_MS = 5_000
    }
}