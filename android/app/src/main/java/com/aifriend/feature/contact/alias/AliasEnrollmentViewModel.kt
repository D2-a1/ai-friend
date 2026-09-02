package com.aifriend.feature.contact.alias

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aifriend.contract.model.AudioPurpose
import com.aifriend.contract.model.Contact
import com.aifriend.contract.model.ContactAlias
import com.aifriend.contract.model.ContactStatus
import com.aifriend.contract.model.CreateAudioUploadTicketRequest
import com.aifriend.core.audio.AudioCapturePort
import com.aifriend.core.audio.AudioPlaybackPort
import com.aifriend.core.audio.AudioPlaybackContent
import com.aifriend.core.audio.CapturedAudio
import com.aifriend.core.audio.VoiceTemplateRecordingNormalizer
import com.aifriend.core.audio.VoiceTemplateRecordingResult
import com.aifriend.core.audio.toAudioCaptureFailurePresentation
import com.aifriend.core.audio.toAudioPlaybackUserMessage
import com.aifriend.core.network.toChineseUserMessage
import com.aifriend.core.voice.LocalVoiceTemplateCandidate
import com.aifriend.core.voice.LocalVoiceTemplateCoordinator
import com.aifriend.core.voice.LocalVoiceTemplateException
import com.aifriend.feature.audio.AudioUploadRepository
import com.aifriend.feature.contact.ContactRepository
import com.aifriend.feature.contact.ui.userFacingLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 称呼双录、本地质量预检、上传和明确保存确认的编排器。
 *
 * <p>原始录音仅保存在私有字段中，不进入 UI 状态。第二遍一致性失败时保留
 * 已通过的第一遍；最终上传失败、取消或页面退出才清理全部本地副本。断网后不保留
 * 待上传任务，不自动补传。
 *
 * @author codex
 * @since 2026-08-12
 */
@HiltViewModel
class AliasEnrollmentViewModel @Inject constructor(
    private val audioCapturePort: AudioCapturePort,
    private val audioPlaybackPort: AudioPlaybackPort,
    private val recordingNormalizer: VoiceTemplateRecordingNormalizer,
    private val audioUploadRepository: AudioUploadRepository,
    private val contactRepository: ContactRepository,
    private val localVoiceTemplateCoordinator: LocalVoiceTemplateCoordinator,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow(AliasEnrollmentUiState())
    val uiState: StateFlow<AliasEnrollmentUiState> = mutableUiState.asStateFlow()

    private var expectedContactVersion: Long? = null
    private var firstRecording: CapturedAudio? = null
    private var secondRecording: CapturedAudio? = null
    private var preparedCandidate: LocalVoiceTemplateCandidate? = null
    private var recordingTimeoutJob: Job? = null
    private var playbackJob: Job? = null
    private var submissionJob: Job? = null
    private var sessionGeneration = 0L

    /**
     * 使用当前联系人列表中的最新对象开始称呼双录。
     *
     * @param contact 当前 owner 已加载的联系人
     */
    fun open(contact: Contact) {
        val generation = ++sessionGeneration
        cancelJobs()
        expectedContactVersion = contact.version
        val available = contact.status in ALIAS_ALLOWED_STATUSES &&
            contact.aliasCount < MAX_ALIASES_PER_CONTACT
        mutableUiState.value = AliasEnrollmentUiState(
            contactId = contact.id,
            contactLabel = contact.userFacingLabel(),
            existingAliases = contact.aliasSummaries(),
            stage = if (available) {
                AliasEnrollmentStage.READY_FIRST
            } else {
                AliasEnrollmentStage.UNAVAILABLE
            },
            errorMessage = when {
                contact.status !in ALIAS_ALLOWED_STATUSES -> "该联系人尚未完成老人手机确认，不能设置称呼"
                contact.aliasCount >= MAX_ALIASES_PER_CONTACT -> "该联系人已有 5 个称呼"
                else -> null
            },
        )
        viewModelScope.launch {
            clearAudioResources()
            if (generation != sessionGeneration) return@launch
        }
    }

    /**
     * 修改称呼展示文字。该文字只作展示，不参与发音唯一性判定。
     */
    fun updateDisplayText(value: String) {
        if (mutableUiState.value.stage in setOf(
                AliasEnrollmentStage.SUBMITTING,
                AliasEnrollmentStage.COMPLETED,
            )
        ) {
            return
        }
        mutableUiState.value = mutableUiState.value.copy(
            displayText = value.take(MAX_DISPLAY_TEXT_LENGTH),
            errorMessage = null,
        )
    }

    /**
     * 开始当前序号的录音。调用前页面必须已取得麦克风权限。
     */
    fun startRecording() {
        val state = mutableUiState.value
        val recordingStage = when (state.stage) {
            AliasEnrollmentStage.READY_FIRST -> AliasEnrollmentStage.RECORDING_FIRST
            AliasEnrollmentStage.FIRST_RECORDED -> AliasEnrollmentStage.RECORDING_SECOND
            else -> return
        }
        val previousStage = state.stage
        val generation = sessionGeneration
        mutableUiState.value = state.copy(
            stage = recordingStage,
            microphonePermissionRecoveryRequired = false,
            errorMessage = null,
            playingRecording = null,
        )
        viewModelScope.launch {
            try {
                audioPlaybackPort.stop()
                audioCapturePort.start(ALIAS_MAX_DURATION_MS)
                ensureCurrentSession(generation)
                recordingTimeoutJob?.cancel()
                recordingTimeoutJob = viewModelScope.launch {
                    delay(ALIAS_MAX_DURATION_MS.toLong())
                    finishRecording()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (generation == sessionGeneration) {
                    val failure = exception.toAudioCaptureFailurePresentation()
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = previousStage,
                        microphonePermissionRecoveryRequired =
                            failure.permissionRecoveryRequired,
                        errorMessage = failure.userMessage,
                    )
                }
            }
        }
    }

    /**
     * 停止当前录音并执行本地质量预检。
     */
    fun finishRecording() {
        val state = mutableUiState.value
        val slot = when (state.stage) {
            AliasEnrollmentStage.RECORDING_FIRST -> AliasRecordingSlot.FIRST
            AliasEnrollmentStage.RECORDING_SECOND -> AliasRecordingSlot.SECOND
            else -> return
        }
        recordingTimeoutJob?.cancel()
        recordingTimeoutJob = null
        val checkingStage = if (slot == AliasRecordingSlot.FIRST) {
            AliasEnrollmentStage.CHECKING_FIRST
        } else {
            AliasEnrollmentStage.CHECKING_SECOND
        }
        val fallbackStage = if (slot == AliasRecordingSlot.FIRST) {
            AliasEnrollmentStage.READY_FIRST
        } else {
            AliasEnrollmentStage.FIRST_RECORDED
        }
        val generation = sessionGeneration
        mutableUiState.value = state.copy(stage = checkingStage)
        viewModelScope.launch {
            var capturedAudio: CapturedAudio? = null
            try {
                capturedAudio = audioCapturePort.stop()
                ensureCurrentSession(generation)
                when (val normalized = recordingNormalizer.normalize(capturedAudio)) {
                    is VoiceTemplateRecordingResult.Passed -> {
                        capturedAudio.clear()
                        capturedAudio = null
                        saveRecording(slot, normalized)
                    }
                    is VoiceTemplateRecordingResult.Rejected -> {
                        capturedAudio.clear()
                        capturedAudio = null
                        mutableUiState.value = mutableUiState.value.copy(
                            stage = fallbackStage,
                            errorMessage = normalized.message,
                        )
                    }
                }
            } catch (exception: CancellationException) {
                capturedAudio?.clear()
                throw exception
            } catch (exception: Exception) {
                capturedAudio?.clear()
                if (generation == sessionGeneration) {
                    val failure = exception.toAudioCaptureFailurePresentation(
                        unknownMessage = "录音处理没有完成，请重新录制",
                    )
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = fallbackStage,
                        microphonePermissionRecoveryRequired =
                            failure.permissionRecoveryRequired,
                        errorMessage = failure.userMessage,
                    )
                }
            }
        }
    }

    /**
     * 麦克风权限被拒绝后显示安全提示，不尝试启动录音。
     */
    fun onMicrophonePermissionDenied() {
        mutableUiState.value = mutableUiState.value.copy(
            microphonePermissionRecoveryRequired = true,
            errorMessage = "需要允许麦克风权限才能录制称呼",
        )
    }

    /**
     * 关闭当前可恢复错误提示。
     */
    fun dismissError() {
        mutableUiState.value = mutableUiState.value.copy(errorMessage = null)
    }

    /** 选择一个现有称呼进入二次确认；录音或提交期间不允许并发删除。 */
    fun requestAliasDeletion(aliasId: String) {
        val state = mutableUiState.value
        if (state.stage !in ALIAS_DELETION_STAGES || state.isDeletingAlias) return
        val alias = state.existingAliases.singleOrNull { it.id == aliasId } ?: return
        mutableUiState.value = state.copy(
            pendingAliasDeletion = alias,
            informationMessage = null,
            errorMessage = null,
        )
    }

    /** 取消称呼删除确认，不改变服务端或本机模板。 */
    fun cancelAliasDeletion() {
        if (mutableUiState.value.isDeletingAlias) return
        mutableUiState.value = mutableUiState.value.copy(pendingAliasDeletion = null)
    }

    /**
     * 明确确认后先停用本机模板，再删除服务端称呼。
     *
     * 本机删除成功但网络失败时保留服务端称呼，允许用户再次重试；错误发音不会继续参与本机匹配。
     */
    fun confirmAliasDeletion() {
        val state = mutableUiState.value
        val contactId = state.contactId ?: return
        val contactVersion = expectedContactVersion ?: return
        val alias = state.pendingAliasDeletion ?: return
        if (state.stage !in ALIAS_DELETION_STAGES || state.isDeletingAlias) return
        val generation = sessionGeneration
        mutableUiState.value = state.copy(
            isDeletingAlias = true,
            informationMessage = null,
            errorMessage = null,
        )
        submissionJob = viewModelScope.launch {
            var localMaterialDeleted = false
            try {
                localVoiceTemplateCoordinator.deleteAliasMaterial(alias.id)
                localMaterialDeleted = true
                ensureCurrentSession(generation)
                val updatedContact = contactRepository.deleteAlias(
                    contactId = contactId,
                    aliasId = alias.id,
                    expectedContactVersion = contactVersion,
                )
                ensureCurrentSession(generation)
                expectedContactVersion = updatedContact.version
                mutableUiState.value = mutableUiState.value.copy(
                    existingAliases = updatedContact.aliasSummaries(),
                    stage = updatedContact.aliasManagementStage(),
                    pendingAliasDeletion = null,
                    isDeletingAlias = false,
                    informationMessage = "称呼“${alias.displayText}”已删除，本机不会再用这个发音匹配",
                    errorMessage = null,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (generation == sessionGeneration) {
                    mutableUiState.value = mutableUiState.value.copy(
                        pendingAliasDeletion = null,
                        isDeletingAlias = false,
                        errorMessage = if (localMaterialDeleted) {
                            "服务端还没有删除这个称呼；本机已停止使用，请再次点击删除"
                        } else {
                            exception.toChineseUserMessage("称呼没有删除，请稍后重试")
                        },
                    )
                }
            }
        }
    }

    /**
     * 回放指定序号的录音。同一时间只允许一段回放。
     */
    fun play(slot: AliasRecordingSlot) {
        if (mutableUiState.value.playingRecording != null ||
            mutableUiState.value.stage in NON_INTERACTIVE_STAGES
        ) {
            return
        }
        val audio = when (slot) {
            AliasRecordingSlot.FIRST -> firstRecording
            AliasRecordingSlot.SECOND -> secondRecording
        } ?: return
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

    /**
     * 重录指定序号。重录第一遍时同时作废第二遍，避免组合旧录音。
     */
    fun retake(slot: AliasRecordingSlot) {
        if (mutableUiState.value.stage in NON_INTERACTIVE_STAGES) return
        playbackJob?.cancel()
        viewModelScope.launch { audioPlaybackPort.stop() }
        when (slot) {
            AliasRecordingSlot.FIRST -> {
                preparedCandidate.clearAndForget()
                preparedCandidate = null
                firstRecording.clearAndForget()
                secondRecording.clearAndForget()
                firstRecording = null
                secondRecording = null
                mutableUiState.value = mutableUiState.value.copy(
                    stage = AliasEnrollmentStage.READY_FIRST,
                    firstDurationMs = null,
                    secondDurationMs = null,
                    playingRecording = null,
                    errorMessage = null,
                )
            }
            AliasRecordingSlot.SECOND -> {
                preparedCandidate.clearAndForget()
                preparedCandidate = null
                secondRecording.clearAndForget()
                secondRecording = null
                mutableUiState.value = mutableUiState.value.copy(
                    stage = AliasEnrollmentStage.FIRST_RECORDED,
                    secondDurationMs = null,
                    playingRecording = null,
                    errorMessage = null,
                )
            }
        }
    }

    /**
     * 用户点击“确认保存称呼”后上传两遍录音并创建称呼。
     */
    fun confirmAndSubmit() {
        val state = mutableUiState.value
        val contactId = state.contactId ?: return
        val contactVersion = expectedContactVersion ?: return
        val displayText = state.displayText.trim()
        val first = firstRecording
        val second = secondRecording
        val candidate = preparedCandidate
        if (state.stage != AliasEnrollmentStage.REVIEW ||
            first == null ||
            second == null ||
            candidate == null
        ) {
            return
        }
        if (displayText.isEmpty()) {
            mutableUiState.value = state.copy(errorMessage = "请输入展示称呼")
            return
        }
        val generation = sessionGeneration
        mutableUiState.value = state.copy(
            stage = AliasEnrollmentStage.SUBMITTING,
            errorMessage = null,
            playingRecording = null,
        )
        playbackJob?.cancel()
        submissionJob = viewModelScope.launch {
            var localCandidate: LocalVoiceTemplateCandidate? = null
            var createdServerAlias: ContactAlias? = null
            try {
                audioPlaybackPort.stop()
                localCandidate = candidate
                val firstAudioObjectId = audioUploadRepository.upload(
                    purpose = AudioPurpose.ALIAS_ENROLLMENT,
                    mediaType = CreateAudioUploadTicketRequest.MediaType.AUDIO_SLASH_WAV,
                    durationMs = first.durationMs,
                    audioContent = first.wavBytes,
                )
                ensureActive()
                ensureCurrentSession(generation)
                val secondAudioObjectId = audioUploadRepository.upload(
                    purpose = AudioPurpose.ALIAS_ENROLLMENT,
                    mediaType = CreateAudioUploadTicketRequest.MediaType.AUDIO_SLASH_WAV,
                    durationMs = second.durationMs,
                    audioContent = second.wavBytes,
                )
                ensureActive()
                ensureCurrentSession(generation)
                val createdAlias = contactRepository.createAlias(
                    contactId = contactId,
                    displayText = displayText,
                    phoneticHint = null,
                    firstAudioObjectId = firstAudioObjectId,
                    secondAudioObjectId = secondAudioObjectId,
                    expectedContactVersion = contactVersion,
                )
                createdServerAlias = createdAlias
                ensureCurrentSession(generation)
                localVoiceTemplateCoordinator.persistAlias(
                    contactId = contactId,
                    alias = createdAlias,
                    candidate = candidate,
                )
                ensureCurrentSession(generation)
                clearRecordedAudio()
                val updatedAliases = mutableUiState.value.existingAliases
                    .filterNot { alias -> alias.id == createdAlias.id } + createdAlias.toUiSummary()
                expectedContactVersion = contactVersion + 1
                mutableUiState.value = mutableUiState.value.copy(
                    stage = AliasEnrollmentStage.COMPLETED,
                    existingAliases = updatedAliases,
                    firstDurationMs = null,
                    secondDurationMs = null,
                    completedAliasText = displayText,
                    canAddAnotherAlias = updatedAliases.size < MAX_ALIASES_PER_CONTACT,
                )
            } catch (exception: CancellationException) {
                clearRecordedAudio()
                throw exception
            } catch (exception: Exception) {
                clearRecordedAudio()
                if (generation == sessionGeneration) {
                    val persistedServerAlias = createdServerAlias
                    val updatedAliases = persistedServerAlias?.let { alias ->
                        expectedContactVersion = contactVersion + 1
                        mutableUiState.value.existingAliases
                            .filterNot { existing -> existing.id == alias.id } + alias.toUiSummary()
                    } ?: mutableUiState.value.existingAliases
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = if (persistedServerAlias == null) {
                            AliasEnrollmentStage.READY_FIRST
                        } else {
                            AliasEnrollmentStage.UNAVAILABLE
                        },
                        existingAliases = updatedAliases,
                        firstDurationMs = null,
                        secondDurationMs = null,
                        canAddAnotherAlias = false,
                        errorMessage = if (persistedServerAlias != null) {
                            "称呼已在服务端保存，但本机模板没有安全落库；" +
                                "本地录音已清理，请先删除该称呼后重新录两遍"
                        } else {
                            exception.toChineseUserMessage("称呼没有保存") +
                                "；本地录音已清理，请重新录两遍"
                        },
                    )
                }
            } finally {
                localCandidate?.clear()
            }
        }
    }

    /** 保存成功且未达到上限时，在当前联系人上开始录制下一个称呼。 */
    fun continueAfterCompletion() {
        val state = mutableUiState.value
        if (state.stage != AliasEnrollmentStage.COMPLETED || !state.canAddAnotherAlias) return
        mutableUiState.value = state.copy(
            displayText = "",
            stage = AliasEnrollmentStage.READY_FIRST,
            firstDurationMs = null,
            secondDurationMs = null,
            playingRecording = null,
            errorMessage = null,
            informationMessage = null,
            completedAliasText = null,
            canAddAnotherAlias = false,
        )
    }

    /**
     * 退出页面，停止录音和回放，并作废所有迟到结果。
     */
    fun leave() {
        ++sessionGeneration
        cancelJobs()
        viewModelScope.launch {
            clearAudioResources()
            expectedContactVersion = null
            mutableUiState.value = AliasEnrollmentUiState()
        }
    }

    override fun onCleared() {
        ++sessionGeneration
        cancelJobs()
        firstRecording.clearAndForget()
        secondRecording.clearAndForget()
        preparedCandidate.clearAndForget()
        firstRecording = null
        secondRecording = null
        preparedCandidate = null
        super.onCleared()
    }

    private fun saveRecording(
        slot: AliasRecordingSlot,
        normalized: VoiceTemplateRecordingResult.Passed,
    ) {
        val capturedAudio = normalized.audio
        when (slot) {
            AliasRecordingSlot.FIRST -> {
                preparedCandidate.clearAndForget()
                preparedCandidate = null
                firstRecording.clearAndForget()
                firstRecording = capturedAudio
                mutableUiState.value = mutableUiState.value.copy(
                    stage = AliasEnrollmentStage.FIRST_RECORDED,
                    firstDurationMs = normalized.durationMs,
                    errorMessage = null,
                )
            }
            AliasRecordingSlot.SECOND -> {
                preparedCandidate.clearAndForget()
                preparedCandidate = null
                secondRecording.clearAndForget()
                secondRecording = capturedAudio
                val first = firstRecording
                if (first == null) {
                    capturedAudio.clear()
                    secondRecording = null
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = AliasEnrollmentStage.READY_FIRST,
                        firstDurationMs = null,
                        secondDurationMs = null,
                        errorMessage = "第一遍录音已失效，请重新录制",
                    )
                    return
                }
                try {
                    preparedCandidate = localVoiceTemplateCoordinator.prepare(
                        first.wavBytes,
                        capturedAudio.wavBytes,
                    )
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = AliasEnrollmentStage.REVIEW,
                        secondDurationMs = normalized.durationMs,
                        errorMessage = null,
                    )
                } catch (exception: Exception) {
                    capturedAudio.clear()
                    secondRecording = null
                    preparedCandidate.clearAndForget()
                    preparedCandidate = null
                    val detail = if (exception is LocalVoiceTemplateException) {
                        exception.message
                    } else {
                        null
                    }
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = AliasEnrollmentStage.FIRST_RECORDED,
                        secondDurationMs = null,
                        errorMessage = detail
                            ?: "两遍发音没有通过一致性检查，请重录第二遍",
                    )
                }
            }
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
        submissionJob?.cancel()
        recordingTimeoutJob = null
        playbackJob = null
        submissionJob = null
    }

    private fun ensureCurrentSession(generation: Long) {
        if (generation != sessionGeneration) throw CancellationException("称呼录制会话已作废")
    }

    private fun CapturedAudio?.clearAndForget() {
        this?.clear()
    }

    private fun LocalVoiceTemplateCandidate?.clearAndForget() {
        this?.clear()
    }

    private fun Contact.aliasSummaries(): List<AliasSummaryUiState> = aliases.orEmpty().map { alias ->
        alias.toUiSummary()
    }

    private fun ContactAlias.toUiSummary(): AliasSummaryUiState =
        AliasSummaryUiState(id = id, displayText = displayText)

    private fun Contact.aliasManagementStage(): AliasEnrollmentStage =
        if (status in ALIAS_ALLOWED_STATUSES && aliasCount < MAX_ALIASES_PER_CONTACT) {
            AliasEnrollmentStage.READY_FIRST
        } else {
            AliasEnrollmentStage.UNAVAILABLE
        }

    private companion object {
        const val ALIAS_MAX_DURATION_MS = 5_000
        const val MAX_ALIASES_PER_CONTACT = 5
        const val MAX_DISPLAY_TEXT_LENGTH = 40
        val ALIAS_ALLOWED_STATUSES = setOf(ContactStatus.ACTIVE_NO_ALIAS, ContactStatus.ACTIVE)
        val NON_INTERACTIVE_STAGES = setOf(
            AliasEnrollmentStage.RECORDING_FIRST,
            AliasEnrollmentStage.CHECKING_FIRST,
            AliasEnrollmentStage.RECORDING_SECOND,
            AliasEnrollmentStage.CHECKING_SECOND,
            AliasEnrollmentStage.SUBMITTING,
            AliasEnrollmentStage.COMPLETED,
        )
        val ALIAS_DELETION_STAGES = setOf(
            AliasEnrollmentStage.READY_FIRST,
            AliasEnrollmentStage.UNAVAILABLE,
        )
    }
}
