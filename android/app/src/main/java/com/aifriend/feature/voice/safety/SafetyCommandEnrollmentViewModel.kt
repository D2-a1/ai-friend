package com.aifriend.feature.voice.safety

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aifriend.contract.model.AudioPurpose
import com.aifriend.contract.model.ConsentDecision
import com.aifriend.contract.model.ConsentType
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
import com.aifriend.core.voice.hasCompleteSafetyCommands
import com.aifriend.feature.audio.AudioUploadRepository
import com.aifriend.feature.auth.AuthApiException
import com.aifriend.feature.consent.ConsentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 四类安全指令授权、逐类双录即时一致性检查和一次性提交编排器。
 *
 * <p>录音字节只保存在私有内存字段中。当前类别的质量或一致性失败只清理该类别的
 * 第二遍，保留已通过类别；每类第二遍完成后立即与已通过类别做同算法区分检查。
 * 服务端明确返回、且确认未提交的可恢复错误会保留八段录音；上传结果不明或其他失败
 * 仍清理全部录音。
 * 不创建离线任务、不自动补传；页面状态只暴露时长和进度。
 *
 * @author codex
 * @since 2026-08-13
 */
@HiltViewModel
class SafetyCommandEnrollmentViewModel @Inject constructor(
    private val audioCapturePort: AudioCapturePort,
    private val audioPlaybackPort: AudioPlaybackPort,
    private val recordingNormalizer: VoiceTemplateRecordingNormalizer,
    private val audioUploadRepository: AudioUploadRepository,
    private val consentRepository: ConsentRepository,
    private val enrollmentRepository: SafetyCommandEnrollmentRepository,
    private val localVoiceTemplateCoordinator: LocalVoiceTemplateCoordinator,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow(SafetyCommandEnrollmentUiState())
    val uiState: StateFlow<SafetyCommandEnrollmentUiState> = mutableUiState.asStateFlow()

    private val recordings = SafetyCommandDefinition.entries.map { RecordingPair() }
    private val preparedCandidates =
        linkedMapOf<com.aifriend.contract.model.SafetyCommandType, LocalVoiceTemplateCandidate>()
    private var recordingTimeoutJob: Job? = null
    private var playbackJob: Job? = null
    private var submissionJob: Job? = null
    private var sessionGeneration = 0L

    /**
     * 打开页面并核对当前语音模板授权。未授权时不会启动录音或上传。
     */
    fun open() {
        val generation = ++sessionGeneration
        cancelJobs()
        mutableUiState.value = freshState(SafetyCommandEnrollmentStage.CHECKING_CONSENT)
        viewModelScope.launch {
            clearAudioResources()
            try {
                val granted = consentRepository.listCurrent().any { consent ->
                    consent.type == ConsentType.VOICE_TEMPLATE &&
                        consent.decision == ConsentDecision.GRANTED &&
                        consent.policyVersion == VOICE_TEMPLATE_POLICY_VERSION
                }
                ensureCurrentSession(generation)
                if (!granted) {
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = SafetyCommandEnrollmentStage.CONSENT_REQUIRED,
                    )
                    return@launch
                }
                showExistingStatusOrRecording(generation)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (generation == sessionGeneration) {
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = SafetyCommandEnrollmentStage.CONSENT_REQUIRED,
                        errorMessage = exception.toChineseUserMessage("无法读取语音模板授权"),
                    )
                }
            }
        }
    }

    /**
     * 用户在独立授权步骤明确允许保存个人语音模板。
     */
    fun grantVoiceTemplateConsent() {
        if (mutableUiState.value.stage != SafetyCommandEnrollmentStage.CONSENT_REQUIRED) return
        val generation = sessionGeneration
        mutableUiState.value = mutableUiState.value.copy(
            stage = SafetyCommandEnrollmentStage.SAVING_CONSENT,
            errorMessage = null,
        )
        viewModelScope.launch {
            try {
                consentRepository.update(
                    type = ConsentType.VOICE_TEMPLATE,
                    decision = ConsentDecision.GRANTED,
                    policyVersion = VOICE_TEMPLATE_POLICY_VERSION,
                )
                ensureCurrentSession(generation)
                showExistingStatusOrRecording(generation)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (generation == sessionGeneration) {
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = SafetyCommandEnrollmentStage.CONSENT_REQUIRED,
                        errorMessage = exception.toChineseUserMessage("语音模板授权没有保存"),
                    )
                }
            }
        }
    }

    /**
     * 用户已看清现有四类模板仍可用后，明确开始整批替换录制。
     */
    fun startFullReplacement() {
        if (mutableUiState.value.stage != SafetyCommandEnrollmentStage.EXISTING_COMPLETE) return
        clearRecordedAudio()
        mutableUiState.value = freshState(SafetyCommandEnrollmentStage.READY_FIRST)
    }

    /**
     * 在尚未录制当前类别时选择一条固定推荐短句。
     *
     * 选择只改变本轮界面提示；声学模板仍只保存发音内容，不转写、不上传展示文字。
     */
    fun selectCurrentPhrase(phraseIndex: Int) {
        val state = mutableUiState.value
        if (state.stage != SafetyCommandEnrollmentStage.READY_FIRST) return
        val progress = state.commands[state.currentCommandIndex]
        if (phraseIndex !in progress.definition.phraseOptions.indices) return
        mutableUiState.value = state.copy(
            commands = state.commands.updated(
                state.currentCommandIndex,
                progress.copy(selectedPhraseIndex = phraseIndex),
            ),
            errorMessage = null,
        )
    }

    /**
     * 开始当前指令当前遍次的录音。调用前页面必须已取得麦克风权限。
     */
    fun startRecording() {
        val state = mutableUiState.value
        val recordingStage = when (state.stage) {
            SafetyCommandEnrollmentStage.READY_FIRST ->
                SafetyCommandEnrollmentStage.RECORDING_FIRST
            SafetyCommandEnrollmentStage.FIRST_RECORDED ->
                SafetyCommandEnrollmentStage.RECORDING_SECOND
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
                audioCapturePort.start(MAX_RECORDING_DURATION_MS)
                ensureCurrentSession(generation)
                recordingTimeoutJob?.cancel()
                recordingTimeoutJob = viewModelScope.launch {
                    delay(MAX_RECORDING_DURATION_MS.toLong())
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
     * 停止当前录音并执行只针对音质的本地预检。
     */
    fun finishRecording() {
        val state = mutableUiState.value
        val take = when (state.stage) {
            SafetyCommandEnrollmentStage.RECORDING_FIRST -> SafetyRecordingTake.FIRST
            SafetyCommandEnrollmentStage.RECORDING_SECOND -> SafetyRecordingTake.SECOND
            else -> return
        }
        recordingTimeoutJob?.cancel()
        recordingTimeoutJob = null
        val checkingStage = if (take == SafetyRecordingTake.FIRST) {
            SafetyCommandEnrollmentStage.CHECKING_FIRST
        } else {
            SafetyCommandEnrollmentStage.CHECKING_SECOND
        }
        val fallbackStage = if (take == SafetyRecordingTake.FIRST) {
            SafetyCommandEnrollmentStage.READY_FIRST
        } else {
            SafetyCommandEnrollmentStage.FIRST_RECORDED
        }
        val generation = sessionGeneration
        val commandIndex = state.currentCommandIndex
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
                        saveRecording(commandIndex, take, normalized)
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
     * 麦克风权限被拒绝时停止在当前步骤。
     */
    fun onMicrophonePermissionDenied() {
        mutableUiState.value = mutableUiState.value.copy(
            microphonePermissionRecoveryRequired = true,
            errorMessage = "需要允许麦克风权限才能录制安全指令",
        )
    }

    /**
     * 回放指定录音。同一时刻只允许回放一段。
     */
    fun play(slot: SafetyRecordingSlot) {
        if (mutableUiState.value.playingRecording != null ||
            mutableUiState.value.stage in NON_INTERACTIVE_STAGES
        ) {
            return
        }
        val audio = recordings.getOrNull(slot.commandIndex)?.get(slot.take) ?: return
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
     * 重录当前指令的指定遍次。重录第一遍会同时作废第二遍。
     */
    fun retake(take: SafetyRecordingTake) {
        val state = mutableUiState.value
        if (state.stage !in setOf(
                SafetyCommandEnrollmentStage.FIRST_RECORDED,
                SafetyCommandEnrollmentStage.REVIEW_COMMAND,
            )
        ) {
            return
        }
        playbackJob?.cancel()
        viewModelScope.launch { audioPlaybackPort.stop() }
        val index = state.currentCommandIndex
        val pair = recordings[index]
        clearPreparedCandidate(state.commands[index].definition.contractType)
        val progress = state.commands[index]
        val nextProgress = when (take) {
            SafetyRecordingTake.FIRST -> {
                pair.clear()
                SafetyCommandProgress(progress.definition)
            }
            SafetyRecordingTake.SECOND -> {
                pair.second.clearAndForget()
                pair.second = null
                progress.copy(secondDurationMs = null, reviewed = false)
            }
        }
        mutableUiState.value = state.copy(
            stage = if (take == SafetyRecordingTake.FIRST) {
                SafetyCommandEnrollmentStage.READY_FIRST
            } else {
                SafetyCommandEnrollmentStage.FIRST_RECORDED
            },
            commands = state.commands.updated(index, nextProgress),
            playingRecording = null,
            errorMessage = null,
        )
    }

    /**
     * 确认已完成本地即时一致性检查的当前指令，并进入下一类。
     */
    fun confirmCurrentCommand() {
        val state = mutableUiState.value
        if (state.stage != SafetyCommandEnrollmentStage.REVIEW_COMMAND) return
        val index = state.currentCommandIndex
        val pair = recordings[index]
        val commandType = state.commands[index].definition.contractType
        if (pair.first == null ||
            pair.second == null ||
            preparedCandidates[commandType] == null
        ) {
            return
        }
        val commands = state.commands.updated(index, state.commands[index].copy(reviewed = true))
        val nextUnreviewedIndex = commands.indexOfFirst { !it.reviewed }
        mutableUiState.value = if (nextUnreviewedIndex == -1) {
            state.copy(
                stage = SafetyCommandEnrollmentStage.REVIEW_ALL,
                commands = commands,
                playingRecording = null,
            )
        } else {
            state.copy(
                stage = SafetyCommandEnrollmentStage.READY_FIRST,
                currentCommandIndex = nextUnreviewedIndex,
                commands = commands,
                playingRecording = null,
            )
        }
    }

    /**
     * 从总览中选择一类重新录制，保留其余尚未提交的内存录音。
     */
    fun redoCommand(commandIndex: Int) {
        val state = mutableUiState.value
        if (state.stage != SafetyCommandEnrollmentStage.REVIEW_ALL ||
            commandIndex !in recordings.indices
        ) {
            return
        }
        recordings[commandIndex].clear()
        clearPreparedCandidate(state.commands[commandIndex].definition.contractType)
        val oldProgress = state.commands[commandIndex]
        val progress = SafetyCommandProgress(
            definition = oldProgress.definition,
            selectedPhraseIndex = oldProgress.selectedPhraseIndex,
        )
        mutableUiState.value = state.copy(
            stage = SafetyCommandEnrollmentStage.READY_FIRST,
            currentCommandIndex = commandIndex,
            commands = state.commands.updated(commandIndex, progress),
            playingRecording = null,
            errorMessage = null,
        )
    }

    /**
     * 用户点击最终明确确认后，依次上传八段音频并提交四类注册。
     */
    fun confirmAndSubmitAll() {
        val state = mutableUiState.value
        if (state.stage != SafetyCommandEnrollmentStage.REVIEW_ALL ||
            !state.commands.all { it.reviewed } ||
            recordings.any { it.first == null || it.second == null } ||
            preparedCandidates.keys != REQUIRED_SAFETY_TYPES
        ) {
            return
        }
        val generation = sessionGeneration
        mutableUiState.value = state.copy(
            stage = SafetyCommandEnrollmentStage.SUBMITTING,
            playingRecording = null,
            errorMessage = null,
        )
        playbackJob?.cancel()
        submissionJob = viewModelScope.launch {
            var serverCommitted = false
            var preserveForRetry = false
            val localCandidates = preparedCandidates.toMap()
            try {
                audioPlaybackPort.stop()
                val uploadedCommands = SafetyCommandDefinition.entries.mapIndexed { index, command ->
                    val pair = recordings[index]
                    val first = requireNotNull(pair.first)
                    val second = requireNotNull(pair.second)
                    val firstId = upload(first)
                    ensureActive()
                    ensureCurrentSession(generation)
                    val secondId = upload(second)
                    ensureActive()
                    ensureCurrentSession(generation)
                    SafetyCommandAudioObjects(command.contractType, firstId, secondId)
                }
                val templates = enrollmentRepository.enroll(
                    commands = uploadedCommands,
                    consentPolicyVersion = VOICE_TEMPLATE_POLICY_VERSION,
                )
                serverCommitted = true
                ensureCurrentSession(generation)
                localVoiceTemplateCoordinator.replaceSafetyCommands(templates, localCandidates)
                ensureCurrentSession(generation)
                clearRecordedAudio()
                mutableUiState.value = mutableUiState.value.copy(
                    stage = SafetyCommandEnrollmentStage.COMPLETED,
                    playingRecording = null,
                    errorMessage = null,
                )
            } catch (exception: CancellationException) {
                clearRecordedAudio()
                if (serverCommitted) {
                    withContext(NonCancellable) {
                        runCatching { localVoiceTemplateCoordinator.reconcile() }
                    }
                }
                throw exception
            } catch (exception: Exception) {
                val canRetryWithoutRecordingAgain = serverCommitted.not() &&
                    exception is AuthApiException && exception.isRecoverableEnrollmentRejection() &&
                    generation == sessionGeneration
                if (canRetryWithoutRecordingAgain) {
                    preserveForRetry = true
                    val retryGuidance = if (
                        exception.stableErrorCode == "ENROLLMENT_INCONSISTENT" ||
                        exception.stableErrorCode == null && exception.httpStatus == 422
                    ) {
                        "八段录音已保留，请只重录容易混淆的指令"
                    } else {
                        "八段录音已保留，请先按提示处理后再次确认保存，无需重新录音"
                    }
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = SafetyCommandEnrollmentStage.REVIEW_ALL,
                        playingRecording = null,
                        errorMessage = exception.toChineseUserMessage("安全指令没有保存") +
                            "；$retryGuidance",
                    )
                } else {
                    clearRecordedAudio()
                }
                if (canRetryWithoutRecordingAgain.not() && generation == sessionGeneration) {
                    val recoveryMessage = if (serverCommitted) {
                        val reconciled = runCatching {
                            localVoiceTemplateCoordinator.reconcile()
                        }.isSuccess
                        if (reconciled) {
                            "服务端已保存新安全指令，但本机模板没有更新；旧本机模板已停用，请全部重新录制恢复"
                        } else {
                            "服务端已保存新安全指令，但本机模板对账失败；当前任务保持不可用，请全部重新录制恢复"
                        }
                    } else {
                        exception.toChineseUserMessage("安全指令没有保存") +
                            "；八段本地录音已清理，请全部重新录制"
                    }
                    mutableUiState.value = freshState(
                        stage = SafetyCommandEnrollmentStage.READY_FIRST,
                        errorMessage = recoveryMessage,
                    )
                }
            } finally {
                if (preserveForRetry.not()) {
                    localCandidates.values.forEach { it.clear() }
                }
            }
        }
    }

    /**
     * 关闭当前提示。
     */
    fun dismissError() {
        mutableUiState.value = mutableUiState.value.copy(errorMessage = null)
    }

    /**
     * 退出页面，停止录音、回放和上传编排，并作废迟到响应。
     */
    fun leave() {
        ++sessionGeneration
        cancelJobs()
        viewModelScope.launch {
            clearAudioResources()
            mutableUiState.value = SafetyCommandEnrollmentUiState()
        }
    }

    override fun onCleared() {
        ++sessionGeneration
        cancelJobs()
        clearRecordedAudio()
        super.onCleared()
    }

    private suspend fun upload(audio: CapturedAudio): String = audioUploadRepository.upload(
        purpose = AudioPurpose.SAFETY_COMMAND_ENROLLMENT,
        mediaType = CreateAudioUploadTicketRequest.MediaType.AUDIO_SLASH_WAV,
        durationMs = audio.durationMs,
        audioContent = audio.wavBytes,
    )

    private fun saveRecording(
        commandIndex: Int,
        take: SafetyRecordingTake,
        normalized: VoiceTemplateRecordingResult.Passed,
    ) {
        val audio = normalized.audio
        val pair = recordings[commandIndex]
        val oldProgress = mutableUiState.value.commands[commandIndex]
        val commandType = oldProgress.definition.contractType
        when (take) {
            SafetyRecordingTake.FIRST -> {
                clearPreparedCandidate(commandType)
                pair.first.clearAndForget()
                pair.second.clearAndForget()
                pair.first = audio
                pair.second = null
                val newProgress = oldProgress.copy(
                    firstDurationMs = normalized.durationMs,
                    secondDurationMs = null,
                    reviewed = false,
                )
                mutableUiState.value = mutableUiState.value.copy(
                    stage = SafetyCommandEnrollmentStage.FIRST_RECORDED,
                    commands = mutableUiState.value.commands.updated(commandIndex, newProgress),
                    errorMessage = null,
                )
            }
            SafetyRecordingTake.SECOND -> {
                clearPreparedCandidate(commandType)
                pair.second.clearAndForget()
                pair.second = audio
                val first = pair.first
                if (first == null) {
                    audio.clear()
                    pair.second = null
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = SafetyCommandEnrollmentStage.READY_FIRST,
                        commands = mutableUiState.value.commands.updated(
                            commandIndex,
                            SafetyCommandProgress(oldProgress.definition),
                        ),
                        errorMessage = "第一遍录音已失效，请重新录制当前指令",
                    )
                    return
                }
                try {
                    val candidate = localVoiceTemplateCoordinator.prepare(
                        first.wavBytes,
                        audio.wavBytes,
                    )
                    preparedCandidates[commandType] = candidate
                    val newProgress = oldProgress.copy(
                        secondDurationMs = normalized.durationMs,
                        reviewed = false,
                    )
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = SafetyCommandEnrollmentStage.REVIEW_COMMAND,
                        commands = mutableUiState.value.commands.updated(commandIndex, newProgress),
                        errorMessage = null,
                    )
                } catch (exception: Exception) {
                    audio.clear()
                    pair.second = null
                    clearPreparedCandidate(commandType)
                    val detail = if (exception is LocalVoiceTemplateException) {
                        exception.message
                    } else {
                        null
                    }
                    mutableUiState.value = mutableUiState.value.copy(
                        stage = SafetyCommandEnrollmentStage.FIRST_RECORDED,
                        commands = mutableUiState.value.commands.updated(
                            commandIndex,
                            oldProgress.copy(secondDurationMs = null, reviewed = false),
                        ),
                        errorMessage = detail
                            ?: "两遍发音没有通过一致性检查，请只重录当前指令第二遍",
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
        recordings.forEach { it.clear() }
        preparedCandidates.values.forEach { it.clear() }
        preparedCandidates.clear()
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
        if (generation != sessionGeneration) {
            throw CancellationException("安全指令录制会话已作废")
        }
    }

    private suspend fun showExistingStatusOrRecording(generation: Long) {
        val status = try {
            val reconciliation = localVoiceTemplateCoordinator.reconcile()
            ensureCurrentSession(generation)
            ExistingSafetyCommandStatus(
                serverTypes = reconciliation.serverSafetyCommandTypes,
                compatibleServerTypes = reconciliation.compatibleServerSafetyCommandTypes,
                localComplete = localVoiceTemplateCoordinator.hasCompleteSafetyCommands(),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            ensureCurrentSession(generation)
            mutableUiState.value = freshState(
                stage = SafetyCommandEnrollmentStage.EXISTING_STATUS_UNAVAILABLE,
                errorMessage = "无法读取已有安全指令状态；为避免覆盖现有模板，本次不会开始重录",
            )
            return
        }
        ensureCurrentSession(generation)
        mutableUiState.value = freshState(
            stage = if (status.serverTypes == REQUIRED_SAFETY_TYPES || status.localComplete) {
                SafetyCommandEnrollmentStage.EXISTING_COMPLETE
            } else {
                SafetyCommandEnrollmentStage.READY_FIRST
            },
            existingServerTemplateTypes = status.serverTypes,
            existingServerTemplatesUsable = status.compatibleServerTypes == REQUIRED_SAFETY_TYPES,
            existingLocalTemplatesReady = status.localComplete,
        )
    }

    private fun freshState(
        stage: SafetyCommandEnrollmentStage,
        errorMessage: String? = null,
        existingServerTemplateTypes: Set<com.aifriend.contract.model.SafetyCommandType> = emptySet(),
        existingServerTemplatesUsable: Boolean = false,
        existingLocalTemplatesReady: Boolean = false,
    ) = SafetyCommandEnrollmentUiState(
        stage = stage,
        errorMessage = errorMessage,
        existingServerTemplateTypes = existingServerTemplateTypes,
        existingServerTemplatesUsable = existingServerTemplatesUsable,
        existingLocalTemplatesReady = existingLocalTemplatesReady,
    )

    private fun <T> List<T>.updated(index: Int, value: T): List<T> =
        toMutableList().also { it[index] = value }

    private fun CapturedAudio?.clearAndForget() {
        this?.clear()
    }

    private fun clearPreparedCandidate(type: com.aifriend.contract.model.SafetyCommandType) {
        preparedCandidates.remove(type)?.clear()
    }

    private fun AuthApiException.isRecoverableEnrollmentRejection(): Boolean =
        stableErrorCode in RECOVERABLE_ENROLLMENT_ERROR_CODES ||
            (stableErrorCode == null && httpStatus == 422)

    private class RecordingPair(
        var first: CapturedAudio? = null,
        var second: CapturedAudio? = null,
    ) {
        fun get(take: SafetyRecordingTake): CapturedAudio? = when (take) {
            SafetyRecordingTake.FIRST -> first
            SafetyRecordingTake.SECOND -> second
        }

        fun clear() {
            first?.clear()
            second?.clear()
            first = null
            second = null
        }
    }

    companion object {
        const val VOICE_TEMPLATE_POLICY_VERSION = "voice-template-v1"
        private const val MAX_RECORDING_DURATION_MS = 5_000
        private val REQUIRED_SAFETY_TYPES =
            SafetyCommandDefinition.entries.map { it.contractType }.toSet()
        private val RECOVERABLE_ENROLLMENT_ERROR_CODES = setOf(
            "CONSENT_REQUIRED",
            "AUDIO_INVALID",
            "ENROLLMENT_INCONSISTENT",
            "TEMPLATE_INCOMPATIBLE",
            "SESSION_CONFLICT",
            "RATE_LIMITED",
            "VALIDATION_FAILED",
        )
        private val NON_INTERACTIVE_STAGES = setOf(
            SafetyCommandEnrollmentStage.IDLE,
            SafetyCommandEnrollmentStage.CHECKING_CONSENT,
            SafetyCommandEnrollmentStage.CONSENT_REQUIRED,
            SafetyCommandEnrollmentStage.SAVING_CONSENT,
            SafetyCommandEnrollmentStage.EXISTING_STATUS_UNAVAILABLE,
            SafetyCommandEnrollmentStage.EXISTING_COMPLETE,
            SafetyCommandEnrollmentStage.RECORDING_FIRST,
            SafetyCommandEnrollmentStage.CHECKING_FIRST,
            SafetyCommandEnrollmentStage.RECORDING_SECOND,
            SafetyCommandEnrollmentStage.CHECKING_SECOND,
            SafetyCommandEnrollmentStage.SUBMITTING,
            SafetyCommandEnrollmentStage.COMPLETED,
        )
    }

    private data class ExistingSafetyCommandStatus(
        val serverTypes: Set<com.aifriend.contract.model.SafetyCommandType>,
        val compatibleServerTypes: Set<com.aifriend.contract.model.SafetyCommandType>,
        val localComplete: Boolean,
    )
}
