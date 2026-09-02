package com.aifriend.feature.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aifriend.contract.model.AudioPurpose
import com.aifriend.contract.model.ConsentDecision
import com.aifriend.contract.model.ConsentType
import com.aifriend.contract.model.CreateAudioUploadTicketRequest
import com.aifriend.contract.model.VoiceCollectionEnvironment
import com.aifriend.core.audio.AliasRecordingQualityAnalyzer
import com.aifriend.core.audio.AliasRecordingQualityResult
import com.aifriend.core.audio.AudioCapturePort
import com.aifriend.core.audio.AudioPlaybackPort
import com.aifriend.core.audio.AudioPlaybackContent
import com.aifriend.core.audio.CapturedAudio
import com.aifriend.core.audio.toAudioCaptureFailurePresentation
import com.aifriend.core.audio.toAudioPlaybackUserMessage
import com.aifriend.core.network.toChineseUserMessage
import com.aifriend.feature.audio.AudioUploadRepository
import com.aifriend.feature.consent.ConsentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 封闭测试语音采集的授权、录音、上传和删除编排器。
 *
 * 原始录音只存在 [capturedAudio] 私有字段，提交、失败、撤权或离开页面都会清零；
 * 不建立离线队列，不自动补传，不调用正式识别、训练或微信执行能力。
 *
 * @author codex
 * @since 2026-08-20
 */
@HiltViewModel
class VoiceCollectionViewModel @Inject constructor(
    private val audioCapturePort: AudioCapturePort,
    private val audioPlaybackPort: AudioPlaybackPort,
    private val qualityAnalyzer: AliasRecordingQualityAnalyzer,
    private val audioUploadRepository: AudioUploadRepository,
    private val consentRepository: ConsentRepository,
    private val collectionRepository: VoiceCollectionRepository,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow(VoiceCollectionUiState())
    val uiState: StateFlow<VoiceCollectionUiState> = mutableUiState.asStateFlow()

    private var capturedAudio: CapturedAudio? = null
    private var sessionGeneration = 0L
    private var operationJob: Job? = null
    private var recordingTimeoutJob: Job? = null
    private var playbackJob: Job? = null

    /** 打开页面并同步独立授权与当前样本。 */
    fun open() {
        val generation = ++sessionGeneration
        cancelJobs()
        mutableUiState.value = VoiceCollectionUiState(stage = VoiceCollectionStage.LOADING)
        operationJob = viewModelScope.launch {
            clearAudioResources()
            try {
                val consents = consentRepository.listCurrent()
                val granted = consents.any { consent ->
                    consent.type == ConsentType.TEST_VOICE_COLLECTION &&
                        consent.decision == ConsentDecision.GRANTED &&
                        consent.policyVersion == VoiceCollectionRepository.POLICY_VERSION
                }
                val trainingGranted = consents.any { consent ->
                    consent.type == ConsentType.VOICE_MODEL_TRAINING &&
                        consent.decision == ConsentDecision.GRANTED &&
                        consent.policyVersion == VoiceCollectionRepository.TRAINING_POLICY_VERSION
                }
                val samples = if (granted) collectionRepository.list() else emptyList()
                ensureCurrent(generation)
                mutableUiState.value = mutableUiState.value.copy(
                    stage = if (granted) {
                        VoiceCollectionStage.READY
                    } else {
                        VoiceCollectionStage.CONSENT_REQUIRED
                    },
                    samples = samples,
                    trainingConsentGranted = trainingGranted,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (generation == sessionGeneration) {
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = VoiceCollectionStage.CONSENT_REQUIRED,
                        errorMessage = exception.toChineseUserMessage("无法读取测试采集状态"),
                    )
                }
            }
        }
    }

    /** 用户阅读说明后保存独立测试语音采集授权。 */
    fun grantConsent() {
        if (mutableUiState.value.stage != VoiceCollectionStage.CONSENT_REQUIRED) return
        val generation = sessionGeneration
        mutableUiState.value = mutableUiState.value.copy(
            stage = VoiceCollectionStage.SAVING_CONSENT,
            errorMessage = null,
        )
        operationJob = viewModelScope.launch {
            try {
                consentRepository.update(
                    ConsentType.TEST_VOICE_COLLECTION,
                    ConsentDecision.GRANTED,
                    VoiceCollectionRepository.POLICY_VERSION,
                )
                ensureCurrent(generation)
                mutableUiState.value = mutableUiState.value.copy(
                    stage = VoiceCollectionStage.READY,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (generation == sessionGeneration) {
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = VoiceCollectionStage.CONSENT_REQUIRED,
                        errorMessage = exception.toChineseUserMessage("采集授权没有保存"),
                    )
                }
            }
        }
    }

    /** 选择当前样本的有限环境分类。 */
    fun selectEnvironment(environment: VoiceCollectionEnvironment) {
        if (mutableUiState.value.stage != VoiceCollectionStage.READY) return
        mutableUiState.value = mutableUiState.value.copy(environment = environment)
    }

    /** 开始录制当前固定提示，页面必须先取得麦克风权限。 */
    fun startRecording() {
        if (mutableUiState.value.stage != VoiceCollectionStage.READY) return
        val generation = sessionGeneration
        mutableUiState.value = mutableUiState.value.copy(
            stage = VoiceCollectionStage.RECORDING,
            microphonePermissionRecoveryRequired = false,
            errorMessage = null,
        )
        operationJob = viewModelScope.launch {
            try {
                audioPlaybackPort.stop()
                audioCapturePort.start(MAX_DURATION_MS)
                ensureCurrent(generation)
                recordingTimeoutJob = viewModelScope.launch {
                    delay(MAX_DURATION_MS.toLong())
                    finishRecording()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (generation == sessionGeneration) {
                    val failure = exception.toAudioCaptureFailurePresentation()
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = VoiceCollectionStage.READY,
                        microphonePermissionRecoveryRequired =
                            failure.permissionRecoveryRequired,
                        errorMessage = failure.userMessage,
                    )
                }
            }
        }
    }

    /** 停止录音并执行不判断内容的本地质量预检。 */
    fun finishRecording() {
        if (mutableUiState.value.stage != VoiceCollectionStage.RECORDING) return
        recordingTimeoutJob?.cancel()
        recordingTimeoutJob = null
        val generation = sessionGeneration
        mutableUiState.value = mutableUiState.value.copy(stage = VoiceCollectionStage.CHECKING)
        operationJob = viewModelScope.launch {
            var audio: CapturedAudio? = null
            try {
                audio = audioCapturePort.stop()
                ensureCurrent(generation)
                when (val quality = qualityAnalyzer.analyze(audio)) {
                    is AliasRecordingQualityResult.Passed -> {
                        capturedAudio?.clear()
                        capturedAudio = audio
                        audio = null
                        mutableUiState.value = mutableUiState.value.copy(
                            stage = VoiceCollectionStage.REVIEW,
                            recordedDurationMs = quality.durationMs,
                            reviewTranscript = mutableUiState.value.currentPrompt
                                .defaultTranscript,
                            reviewPlaybackCompleted = false,
                        )
                    }
                    is AliasRecordingQualityResult.Rejected -> {
                        mutableUiState.value = mutableUiState.value.copy(
                            stage = VoiceCollectionStage.READY,
                            errorMessage = quality.message,
                        )
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (generation == sessionGeneration) {
                    val failure = exception.toAudioCaptureFailurePresentation(
                        unknownMessage = "录音处理没有完成，请重新录制",
                    )
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = VoiceCollectionStage.READY,
                        microphonePermissionRecoveryRequired =
                            failure.permissionRecoveryRequired,
                        errorMessage = failure.userMessage,
                    )
                }
            } finally {
                audio?.clear()
            }
        }
    }

    /** 麦克风权限拒绝时保持失败关闭。 */
    fun onMicrophonePermissionDenied() {
        mutableUiState.value = mutableUiState.value.withMicrophonePermissionDenied()
    }

    /** 回放当前进程内的录音。 */
    fun play() {
        val audio = capturedAudio ?: return
        if (mutableUiState.value.stage != VoiceCollectionStage.REVIEW ||
            mutableUiState.value.isPlaying
        ) {
            return
        }
        val generation = sessionGeneration
        playbackJob = viewModelScope.launch {
            mutableUiState.value = mutableUiState.value.copy(isPlaying = true)
            var playbackCompleted = false
            try {
                audioPlaybackPort.play(audio.wavBytes)
                playbackCompleted = true
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
                    mutableUiState.value = mutableUiState.value.copy(
                        isPlaying = false,
                        reviewPlaybackCompleted =
                            mutableUiState.value.reviewPlaybackCompleted || playbackCompleted,
                    )
                }
            }
        }
    }

    /** 清零当前录音并返回当前固定提示。 */
    fun retake() {
        if (mutableUiState.value.stage != VoiceCollectionStage.REVIEW) return
        playbackJob?.cancel()
        viewModelScope.launch { audioPlaybackPort.stop() }
        clearCapturedAudio()
        mutableUiState.value = mutableUiState.value.copy(
            stage = VoiceCollectionStage.READY,
            recordedDurationMs = null,
            reviewTranscript = "",
            reviewPlaybackCompleted = false,
            isPlaying = false,
        )
    }

    /** 更新仍在本机内存中的当前录音人工复核文字。 */
    fun updateReviewTranscript(transcript: String) {
        if (mutableUiState.value.stage != VoiceCollectionStage.REVIEW) return
        mutableUiState.value = mutableUiState.value.copy(
            reviewTranscript = transcript.take(MAX_REVIEW_TRANSCRIPT_LENGTH),
        )
    }

    /** 明确提交当前样本；失败后清零且不自动重试。 */
    fun submit() {
        val audio = capturedAudio ?: return
        val state = mutableUiState.value
        if (state.stage != VoiceCollectionStage.REVIEW) return
        val prompt = state.currentPrompt
        val environment = state.environment
        val reviewedTranscript = state.reviewTranscript.trim().replace(Regex("\\s+"), " ")
        if (!state.reviewPlaybackCompleted) {
            mutableUiState.value = state.copy(errorMessage = "请先完整试听当前录音")
            return
        }
        if (reviewedTranscript.isBlank()) {
            mutableUiState.value = state.copy(errorMessage = "请先试听并填写实际说出的文字")
            return
        }
        val generation = sessionGeneration
        mutableUiState.value = state.copy(
            stage = VoiceCollectionStage.SUBMITTING,
            isPlaying = false,
            errorMessage = null,
        )
        playbackJob?.cancel()
        operationJob = viewModelScope.launch {
            try {
                audioPlaybackPort.stop()
                val audioObjectId = audioUploadRepository.upload(
                    purpose = AudioPurpose.TEST_VOICE_COLLECTION,
                    mediaType = CreateAudioUploadTicketRequest.MediaType.AUDIO_SLASH_WAV,
                    durationMs = audio.durationMs,
                    audioContent = audio.wavBytes,
                )
                ensureCurrent(generation)
                val created = collectionRepository.create(
                    audioObjectId = audioObjectId,
                    category = prompt.category,
                    promptCode = prompt.code,
                    environment = environment,
                    reviewedTranscript = reviewedTranscript,
                )
                ensureCurrent(generation)
                clearCapturedAudio()
                val nextIndex = (state.promptIndex + 1) % VOICE_COLLECTION_PROMPTS.size
                mutableUiState.value = mutableUiState.value.copy(
                    stage = VoiceCollectionStage.READY,
                    promptIndex = nextIndex,
                    recordedDurationMs = null,
                    reviewTranscript = "",
                    reviewPlaybackCompleted = false,
                    samples = listOf(created) + mutableUiState.value.samples,
                )
            } catch (exception: CancellationException) {
                clearCapturedAudio()
                throw exception
            } catch (exception: Exception) {
                clearCapturedAudio()
                if (generation == sessionGeneration) {
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = VoiceCollectionStage.READY,
                        recordedDurationMs = null,
                        reviewTranscript = "",
                        reviewPlaybackCompleted = false,
                        errorMessage = exception.toChineseUserMessage("样本没有提交") +
                            "；本地录音已清理，请重新录制",
                    )
                }
            }
        }
    }

    /** 第一次点击只进入删除确认，不立即发请求。 */
    fun requestDelete(sampleId: String) {
        if (mutableUiState.value.stage != VoiceCollectionStage.READY) return
        mutableUiState.value = mutableUiState.value.copy(pendingDeleteSampleId = sampleId)
    }

    /** 取消单条样本删除确认。 */
    fun cancelDelete() {
        mutableUiState.value = mutableUiState.value.copy(pendingDeleteSampleId = null)
    }

    /** 第二次明确确认后受理单条样本删除。 */
    fun confirmDelete() {
        val state = mutableUiState.value
        val sample = state.samples.firstOrNull { it.sampleId == state.pendingDeleteSampleId }
            ?: return
        val generation = sessionGeneration
        mutableUiState.value = state.copy(
            stage = VoiceCollectionStage.DELETING,
            errorMessage = null,
        )
        operationJob = viewModelScope.launch {
            try {
                collectionRepository.delete(sample.sampleId, sample.version)
                ensureCurrent(generation)
                mutableUiState.value = mutableUiState.value.copy(
                    stage = VoiceCollectionStage.READY,
                    samples = mutableUiState.value.samples.filterNot {
                        it.sampleId == sample.sampleId
                    },
                    pendingDeleteSampleId = null,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (generation == sessionGeneration) {
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = VoiceCollectionStage.READY,
                        pendingDeleteSampleId = null,
                        errorMessage = exception.toChineseUserMessage("删除请求没有受理"),
                    )
                }
            }
        }
    }

    /** 第一次点击只展示当前样本训练授权范围，不立即提交。 */
    fun requestTrainingAuthorization(sampleId: String) {
        if (mutableUiState.value.stage != VoiceCollectionStage.READY) return
        mutableUiState.value = mutableUiState.value.requestTrainingAuthorization(sampleId)
    }

    /** 取消单条样本训练授权确认。 */
    fun cancelTrainingAuthorization() {
        mutableUiState.value = mutableUiState.value.copy(
            pendingTrainingSampleId = null,
            pendingTrainingDecision = null,
        )
    }

    /** 二次明确确认后授予或撤回单条样本训练资格。 */
    fun confirmTrainingAuthorization() {
        val state = mutableUiState.value
        val sample = state.samples.firstOrNull {
            it.sampleId == state.pendingTrainingSampleId
        } ?: return
        val decision = state.pendingTrainingDecision ?: return
        val generation = sessionGeneration
        mutableUiState.value = state.copy(
            stage = VoiceCollectionStage.UPDATING_TRAINING_AUTHORIZATION,
            errorMessage = null,
        )
        operationJob = viewModelScope.launch {
            var trainingConsentGranted = state.trainingConsentGranted
            try {
                if (decision == ConsentDecision.GRANTED && !state.trainingConsentGranted) {
                    consentRepository.update(
                        ConsentType.VOICE_MODEL_TRAINING,
                        ConsentDecision.GRANTED,
                        VoiceCollectionRepository.TRAINING_POLICY_VERSION,
                    )
                    ensureCurrent(generation)
                    trainingConsentGranted = true
                }
                val authorization = collectionRepository.updateTrainingAuthorization(
                    sample.sampleId,
                    sample.version,
                    decision,
                )
                ensureCurrent(generation)
                mutableUiState.value = mutableUiState.value
                    .applyTrainingAuthorization(authorization)
                    .copy(stage = VoiceCollectionStage.READY)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (generation == sessionGeneration) {
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = VoiceCollectionStage.READY,
                        pendingTrainingSampleId = null,
                        pendingTrainingDecision = null,
                        trainingConsentGranted = trainingConsentGranted,
                        errorMessage = if (trainingConsentGranted &&
                            !state.trainingConsentGranted
                        ) {
                            "训练总授权已保存，但这条样本尚未授权；可以再次选择这条样本"
                        } else {
                            exception.toChineseUserMessage("训练授权没有保存")
                        },
                    )
                }
            }
        }
    }

    /** 第一次点击只展示全部训练授权撤回范围。 */
    fun requestRevokeTrainingConsent() {
        if (mutableUiState.value.stage != VoiceCollectionStage.READY ||
            !mutableUiState.value.trainingConsentGranted
        ) {
            return
        }
        mutableUiState.value = mutableUiState.value.copy(
            revokeTrainingConsentConfirmationPending = true,
        )
    }

    /** 取消撤回全部训练授权。 */
    fun cancelRevokeTrainingConsent() {
        mutableUiState.value = mutableUiState.value.copy(
            revokeTrainingConsentConfirmationPending = false,
        )
    }

    /** 二次明确确认后撤回全局训练授权并刷新样本版本。 */
    fun confirmRevokeTrainingConsent() {
        if (!mutableUiState.value.revokeTrainingConsentConfirmationPending) return
        val generation = sessionGeneration
        var revoked = false
        mutableUiState.value = mutableUiState.value.copy(
            stage = VoiceCollectionStage.REVOKING_TRAINING_CONSENT,
            errorMessage = null,
        )
        operationJob = viewModelScope.launch {
            try {
                consentRepository.update(
                    ConsentType.VOICE_MODEL_TRAINING,
                    ConsentDecision.REVOKED,
                    VoiceCollectionRepository.TRAINING_POLICY_VERSION,
                )
                revoked = true
                ensureCurrent(generation)
                val samples = collectionRepository.list()
                ensureCurrent(generation)
                mutableUiState.value = mutableUiState.value.copy(
                    stage = VoiceCollectionStage.READY,
                    trainingConsentGranted = false,
                    revokeTrainingConsentConfirmationPending = false,
                    samples = samples,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (generation == sessionGeneration) {
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = VoiceCollectionStage.READY,
                        trainingConsentGranted = if (revoked) false else {
                            mutableUiState.value.trainingConsentGranted
                        },
                        samples = if (revoked) {
                            mutableUiState.value.samples.map {
                                it.copy(trainingEligible = false)
                            }
                        } else {
                            mutableUiState.value.samples
                        },
                        revokeTrainingConsentConfirmationPending = false,
                        errorMessage = if (revoked) {
                            "训练授权已撤回；请重新进入本页刷新样本版本"
                        } else {
                            exception.toChineseUserMessage("训练授权撤回没有完成")
                        },
                    )
                }
            }
        }
    }

    /** 第一次点击只显示撤权影响，不立即撤回。 */
    fun requestRevokeConsent() {
        if (mutableUiState.value.stage != VoiceCollectionStage.READY) return
        mutableUiState.value = mutableUiState.value.copy(revokeConfirmationPending = true)
    }

    /** 取消撤回采集授权确认。 */
    fun cancelRevokeConsent() {
        mutableUiState.value = mutableUiState.value.copy(revokeConfirmationPending = false)
    }

    /** 第二次明确确认后撤权；服务端会把全部有效样本进入删除队列。 */
    fun confirmRevokeConsent() {
        if (!mutableUiState.value.revokeConfirmationPending) return
        val generation = sessionGeneration
        mutableUiState.value = mutableUiState.value.copy(
            stage = VoiceCollectionStage.REVOKING,
            errorMessage = null,
        )
        operationJob = viewModelScope.launch {
            clearAudioResources()
            try {
                consentRepository.update(
                    ConsentType.TEST_VOICE_COLLECTION,
                    ConsentDecision.REVOKED,
                    VoiceCollectionRepository.POLICY_VERSION,
                )
                ensureCurrent(generation)
                mutableUiState.value = VoiceCollectionUiState(
                    stage = VoiceCollectionStage.CONSENT_REQUIRED,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (generation == sessionGeneration) {
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = VoiceCollectionStage.READY,
                        revokeConfirmationPending = false,
                        errorMessage = exception.toChineseUserMessage("授权撤回没有完成"),
                    )
                }
            }
        }
    }

    fun dismissError() {
        mutableUiState.value = mutableUiState.value.copy(errorMessage = null)
    }

    /** 离开页面时作废迟到响应并清理当前录音。 */
    fun leave() {
        ++sessionGeneration
        cancelJobs()
        viewModelScope.launch {
            clearAudioResources()
            mutableUiState.value = VoiceCollectionUiState()
        }
    }

    override fun onCleared() {
        ++sessionGeneration
        cancelJobs()
        capturedAudio?.clear()
        capturedAudio = null
        super.onCleared()
    }

    private suspend fun clearAudioResources() {
        recordingTimeoutJob?.cancel()
        runCatching { audioCapturePort.cancel() }
        runCatching { audioPlaybackPort.stop() }
        clearCapturedAudio()
    }

    private fun clearCapturedAudio() {
        capturedAudio?.clear()
        capturedAudio = null
    }

    private fun cancelJobs() {
        operationJob?.cancel()
        recordingTimeoutJob?.cancel()
        playbackJob?.cancel()
        operationJob = null
        recordingTimeoutJob = null
        playbackJob = null
    }

    private fun ensureCurrent(generation: Long) {
        if (generation != sessionGeneration) {
            throw CancellationException("测试语音采集会话已作废")
        }
    }

    private companion object {
        const val MAX_DURATION_MS = 5_000
        const val MAX_REVIEW_TRANSCRIPT_LENGTH = 120
    }
}
