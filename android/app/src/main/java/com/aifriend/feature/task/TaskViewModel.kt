package com.aifriend.feature.task

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aifriend.BuildConfig
import com.aifriend.contract.model.AllowedAction
import com.aifriend.contract.model.AudioPurpose
import com.aifriend.contract.model.ConfirmationAction
import com.aifriend.contract.model.ConsentDecision
import com.aifriend.contract.model.ConsentType
import com.aifriend.contract.model.CreateAudioUploadTicketRequest
import com.aifriend.contract.model.TaskClientContext
import com.aifriend.contract.model.TaskState
import com.aifriend.contract.model.TaskRevisionMode
import com.aifriend.contract.model.Intent
import com.aifriend.contract.model.WechatActionType
import com.aifriend.core.audio.AudioCapturePort
import com.aifriend.core.audio.AudioCaptureReleaseCoordinator
import com.aifriend.core.audio.AudioPlaybackContent
import com.aifriend.core.audio.CapturedAudio
import com.aifriend.core.audio.SpeechEndpointBoundary
import com.aifriend.core.audio.toAudioCaptureFailurePresentation
import com.aifriend.core.audio.toAudioPlaybackUserMessage
import com.aifriend.core.feedback.HapticFeedbackPort
import com.aifriend.core.network.toChineseUserMessage
import com.aifriend.core.voice.DialectPackageRegistry
import com.aifriend.feature.audio.AudioUploadRepository
import com.aifriend.feature.guardian.GuardianTaskInbox
import com.aifriend.feature.guardian.GuardianWechatCallAudioCoordinator
import com.aifriend.feature.personalization.DisabledPersonalMemoryRepository
import com.aifriend.feature.personalization.PersonalMemoryRepository
import com.aifriend.feature.consent.ConsentRepository
import com.aifriend.feature.wechat.WechatExecutionCoordinator
import com.aifriend.feature.wechat.WechatMessageDeliveryCoordinator
import com.aifriend.feature.wechat.WechatMessageHandoffPortFactory
import com.aifriend.feature.wechat.WechatSemanticCallContract
import com.aifriend.feature.wechat.WechatSemanticCallExecutionOutcome
import com.aifriend.feature.wechat.WechatSemanticCallExecutionStatus
import com.aifriend.feature.wechat.WechatCallChoiceActionOutcome
import com.aifriend.feature.wechat.WechatCallChoiceActionStatus
import com.aifriend.feature.wechat.WechatCallStartedTransitionOutcome
import com.aifriend.feature.wechat.WechatCallStartedTransitionStatus
import com.aifriend.feature.wechat.WechatDirectChatTransitionOutcome
import com.aifriend.feature.wechat.WechatDirectChatTransitionStatus
import com.aifriend.feature.wechat.WechatExecutionDenial
import com.aifriend.feature.wechat.WechatVerifiedContactProfileActionOutcome
import com.aifriend.feature.wechat.WechatVerifiedContactProfileActionStatus
import com.aifriend.feature.wechat.unsupportedWechatMessageDeliveryReport
import com.aifriend.feature.wechat.toDeliveryReport
import com.aifriend.feature.wechat.failedWechatCallDeliveryReport
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.OffsetDateTime
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * TASK 录音上传、会话状态与本地动作型确认编排器。
 *
 * 原始音频只存在私有字段；失败、退出、终态或进程销毁时清零。不恢复旧任务、
 * 不离线补传。只有最终确认后的当前计划通过安全准入，才允许请求一次微信动作。
 * 消息严格先把可播放原声文件交给微信，收到成功回调后才继续参考文字；通话只在短时窗口内
 * 核对资料页和通话类型。两者最终都只报告已交给微信处理，不冒充送达、接通或对方已接听。
 *
 * @author codex
 * @since 2026-08-13
 */
@HiltViewModel
class TaskViewModel @Inject constructor(
    private val audioCapturePort: AudioCapturePort,
    private val audioUploadRepository: AudioUploadRepository,
    private val consentRepository: ConsentRepository,
    private val taskRepository: TaskRepository,
    private val localTaskRecognizer: LocalTaskRecognizer,
    private val dialectPackageRegistry: DialectPackageRegistry,
    private val guardianTaskInbox: GuardianTaskInbox,
    private val guardianWechatCallAudioCoordinator: GuardianWechatCallAudioCoordinator,
    private val executionGate: TaskExecutionGate,
    private val wechatExecutionCoordinator: WechatExecutionCoordinator,
    private val wechatMessageDeliveryCoordinator: WechatMessageDeliveryCoordinator,
    private val wechatMessageHandoffPortFactory: WechatMessageHandoffPortFactory,
    private val rehearsalCoordinator: TaskRehearsalCoordinator,
    private val hapticFeedbackPort: HapticFeedbackPort,
    private val continuationAnnouncer: MessageContinuationAnnouncer,
    private val personalMemoryRepository: PersonalMemoryRepository =
        DisabledPersonalMemoryRepository,
) : ViewModel() {

    private val recordingRelease = AudioCaptureReleaseCoordinator(audioCapturePort, viewModelScope)

    private val mutableUiState = MutableStateFlow(TaskUiState())
    val uiState: StateFlow<TaskUiState> = mutableUiState.asStateFlow()
    val pendingGuardianHandoff = guardianTaskInbox.signal
    private val wechatLaunchRequestsChannel = Channel<Unit>(capacity = 1)
    val wechatLaunchRequests = wechatLaunchRequestsChannel.receiveAsFlow()
    private var taskAudio: CapturedAudio? = null
    private var confirmationAudio: CapturedAudio? = null
    private var activeJob: Job? = null
    private var continuationTimerJob: Job? = null
    private var continuationSpeechJob: Job? = null
    private var wechatTransitionTimeoutJob: Job? = null
    private var confirmationTimeoutJob: Job? = null
    private var confirmationEndpointJob: Job? = null
    private var candidateSelectionTimeoutJob: Job? = null
    private var revisionTimeoutJob: Job? = null
    private var revisionEndpointJob: Job? = null
    private var pendingRevisionMode: TaskRevisionMode? = null
    private var generation = 0L
    private var executionToken = executionGate.open()
    private var guardianResumeEligible = false
    private var pendingWechatActionPlan: com.aifriend.contract.model.WechatActionPlan? = null
    private var pendingWechatMessageAudio: CapturedAudio? = null
    private var pendingWechatMessageSessionId: String? = null
    private var rehearsedSummaryHash: String? = null
    private var pendingPreviousConfirmedContactId: String? = null
    private var confirmationAttemptCount = 0
    private var candidateSelectionAttemptCount = 0
    private val continuationWindow = MessageContinuationWindow(SystemClock::elapsedRealtime)

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutableUiState
                .map { state ->
                    TaskFeedbackKey(state.stage, state.session?.state) to
                        state.feedbackPresentation().hapticCue
                }
                .distinctUntilChanged()
                .drop(1)
                .collect { (_, cue) ->
                    hapticFeedbackPort.emit(cue)
                }
        }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            wechatExecutionCoordinator.publishedPlans.collect(::handlePublishedWechatPage)
        }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            wechatExecutionCoordinator.actionOutcomes.collect(::handleWechatActionOutcome)
        }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            wechatExecutionCoordinator.directChatTransitionOutcomes.collect(
                ::handleDirectChatTransitionOutcome,
            )
        }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            wechatExecutionCoordinator.callChoiceActionOutcomes.collect(
                ::handleCallChoiceActionOutcome,
            )
        }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            wechatExecutionCoordinator.callStartedTransitionOutcomes.collect(
                ::handleCallStartedTransitionOutcome,
            )
        }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            wechatExecutionCoordinator.semanticCallOutcomes.collect(
                ::handleSemanticCallOutcome,
            )
        }
    }

    fun open() {
        generation++
        executionToken = executionGate.open()
        personalMemoryRepository.invalidate()
        viewModelScope.launch {
            try {
                personalMemoryRepository.read()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                personalMemoryRepository.invalidate()
            }
        }
        guardianResumeEligible = false
        activeJob?.cancel()
        clearContinuationWindow()
        rehearsalCoordinator.close()
        viewModelScope.launch { rehearsalCoordinator.stop() }
        clearAudio()
        clearWechatActionPlan()
        rehearsedSummaryHash = null
        guardianTaskInbox.clear()
        mutableUiState.value = TaskUiState(
            stage = TaskStage.CHECKING_CONSENT,
            statusMessage = "正在核对当前任务语音授权",
        )
        val current = generation
        val token = executionToken
        activeJob = viewModelScope.launch {
            try {
                val granted = consentRepository.listCurrent()
                    .hasTaskAudioConsent(TASK_AUDIO_POLICY_VERSION)
                ensureCurrent(current, token)
                mutableUiState.value = if (granted) {
                    TaskUiState()
                } else {
                    TaskUiState(
                        stage = TaskStage.CONSENT_REQUIRED,
                        statusMessage = "请先确认是否允许处理当前任务语音",
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (current == generation && executionGate.canContinue(token)) {
                    mutableUiState.value = TaskUiState(
                        stage = TaskStage.CONSENT_REQUIRED,
                        statusMessage = "暂时无法确认任务语音授权",
                        errorMessage = exception.toChineseUserMessage("无法读取任务语音授权"),
                    )
                }
            }
        }
    }

    /** 用户看清用途后明确允许处理当前任务语音；不会替用户自动同意。 */
    fun grantTaskAudioConsent() {
        if (mutableUiState.value.stage != TaskStage.CONSENT_REQUIRED) return
        val current = generation
        val token = executionToken
        mutableUiState.value = mutableUiState.value.copy(
            stage = TaskStage.SAVING_CONSENT,
            statusMessage = "正在保存您的选择",
            errorMessage = null,
        )
        activeJob = viewModelScope.launch {
            try {
                consentRepository.update(
                    type = ConsentType.TASK_AUDIO,
                    decision = ConsentDecision.GRANTED,
                    policyVersion = TASK_AUDIO_POLICY_VERSION,
                )
                ensureCurrent(current, token)
                mutableUiState.value = TaskUiState()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (current == generation && executionGate.canContinue(token)) {
                    mutableUiState.value = TaskUiState(
                        stage = TaskStage.CONSENT_REQUIRED,
                        statusMessage = "任务语音授权没有保存",
                        errorMessage = exception.toChineseUserMessage("任务语音授权没有保存"),
                    )
                }
            }
        }
    }

    /** 接收守护服务刚创建的当前进程任务；会话只允许消费一次。 */
    fun adoptGuardianSession(sessionId: String) {
        val handoff = guardianTaskInbox.take(sessionId) ?: return
        generation++
        executionToken = executionGate.open()
        guardianResumeEligible = handoff.guardianResumeEligible
        activeJob?.cancel()
        clearContinuationWindow()
        rehearsalCoordinator.close()
        clearAudio()
        taskAudio = handoff.taskAudio
        clearWechatActionPlan()
        rehearsedSummaryHash = null
        showSession(handoff.session)
    }

    /** 守护资源未能按时释放时消费并清除交接音频，绝不启动播报、录音或动作。 */
    fun rejectGuardianSessionHandoff(sessionId: String, message: String) {
        val handoff = guardianTaskInbox.take(sessionId) ?: return
        handoff.taskAudio.clear()
        generation++
        activeJob?.cancel()
        guardianResumeEligible = false
        failAndClear(message)
    }

    fun startTaskRecording() {
        if (mutableUiState.value.stage != TaskStage.READY) return
        pendingPreviousConfirmedContactId = null
        clearContinuationWindow()
        startRecording(TaskStage.RECORDING_TASK, TASK_MAX_DURATION_MS)
    }

    /** 麦克风权限未取得时保留当前业务状态，不启动采集或网络调用。 */
    fun onMicrophonePermissionDenied() {
        mutableUiState.value = mutableUiState.value.withMicrophonePermissionDenied()
    }

    /** 丢弃旧会话和动作计划，以全新本机代次重新采集任务语音。 */
    fun repeatTaskRecording() {
        val state = mutableUiState.value
        val retryAllowed = state.stage == TaskStage.REHEARSAL_FAILED ||
            state.stage == TaskStage.ACTIVE &&
            AllowedAction.RETRY in state.session?.allowedActions.orEmpty()
        if (!retryAllowed) return
        startFreshTaskRecording(previousConfirmedContactId = null)
    }

    fun finishTaskRecording() {
        if (mutableUiState.value.stage != TaskStage.RECORDING_TASK) return
        val current = generation
        val token = executionToken
        val previousConfirmedContactId = pendingPreviousConfirmedContactId
        pendingPreviousConfirmedContactId = null
        mutableUiState.value = mutableUiState.value.copy(
            stage = TaskStage.SUBMITTING_TASK,
            statusMessage = "正在理解这句话，请稍等",
            errorMessage = null,
        )
        activeJob = viewModelScope.launch {
            try {
                val captured = try {
                    audioCapturePort.stop()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    val failure = exception.toAudioCaptureFailurePresentation(
                        unknownMessage = "录音处理没有完成，请重新录制",
                    )
                    failIfCurrent(
                        current,
                        token,
                        failure.userMessage,
                        failure.permissionRecoveryRequired,
                    )
                    return@launch
                }
                ensureCurrent(current, token)
                taskAudio?.clear()
                taskAudio = captured
                val packageInfo = dialectPackageRegistry.activePackage()
                val basicExperience = BuildConfig.BASIC_EXPERIENCE_ENABLED &&
                    !dialectPackageRegistry.formalPackageAvailable()
                if (packageInfo == null && !basicExperience) {
                    error("当前语音识别暂不可用，请稍后再试")
                }
                val audioObjectId = manualTaskSubmissionStage(
                    stage = ManualTaskSubmissionStage.AUDIO_UPLOAD,
                    fallback = "任务录音上传阶段没有完成，录音已清除",
                ) {
                    audioUploadRepository.upload(
                        AudioPurpose.TASK,
                        CreateAudioUploadTicketRequest.MediaType.AUDIO_SLASH_WAV,
                        captured.durationMs,
                        captured.wavBytes,
                    )
                }
                ensureCurrent(current, token)
                val currentWechatVersion = wechatExecutionCoordinator.currentWechatVersion()
                val session = manualTaskSubmissionStage(
                    stage = ManualTaskSubmissionStage.TASK_CREATION,
                    fallback = "本机识别或服务器创建任务阶段没有完成，录音已清除",
                ) {
                    taskRepository.create(
                        audioObjectId,
                        if (packageInfo != null) {
                            val manifest = packageInfo.manifest
                            TaskClientContext(
                            appVersion = BuildConfig.VERSION_NAME,
                            wechatVersion = currentWechatVersion ?: WECHAT_VERSION_UNVERIFIED,
                            ruleVersion = WechatSemanticCallContract.RULE_VERSION,
                            dialectCode = manifest.dialectCode,
                            dialectPackageVersion = manifest.packageVersion,
                            mandarinAssistVersion = manifest.mandarinAssistVersion,
                            fusionRuleVersion = manifest.fusionRuleVersion,
                            templateModelVersion = manifest.acousticModelVersion,
                            thresholdVersion = manifest.thresholdVersion,
                        )
                        } else {
                            basicTaskContext(currentWechatVersion)
                        },
                        previousConfirmedContactId,
                        basicRecognitionAudio = captured.takeIf { basicExperience },
                    )
                }
                ensureCurrent(current, token)
                showSession(session)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                val stage = (exception as? ManualTaskSubmissionException)?.stage?.name
                    ?: "UNKNOWN"
                Log.w(
                    TASK_SUBMISSION_TAG,
                    "Manual task submission failed at stage=$stage " +
                        exception.manualTaskDiagnosticSummary(),
                )
                failIfCurrent(
                    current,
                    token,
                    exception.toChineseUserMessage("任务没有完成，请重新说"),
                )
            }
        }
    }

    private fun basicTaskContext(
        currentWechatVersion: String?,
    ) = TaskClientContext(
        appVersion = BuildConfig.VERSION_NAME,
        wechatVersion = currentWechatVersion ?: WECHAT_VERSION_UNVERIFIED,
        ruleVersion = WechatSemanticCallContract.RULE_VERSION,
        dialectCode = BasicExperienceTaskContext.DIALECT_CODE,
        dialectPackageVersion = BasicExperienceTaskContext.PACKAGE_VERSION,
        mandarinAssistVersion = BasicExperienceTaskContext.ASR_MODEL_VERSION,
        fusionRuleVersion = BasicExperienceTaskContext.FUSION_RULE_VERSION,
        templateModelVersion = BasicExperienceTaskContext.ACOUSTIC_MODEL_VERSION,
        thresholdVersion = BasicExperienceTaskContext.THRESHOLD_VERSION,
    )

    fun selectCandidate(candidateId: String) {
        val session = mutableUiState.value.session ?: return
        val current = generation
        val token = executionToken
        mutableUiState.value = mutableUiState.value.copy(
            stage = TaskStage.SUBMITTING_TASK,
            statusMessage = "正在准备完整复述",
            errorMessage = null,
        )
        activeJob = viewModelScope.launch {
            try {
                val updated = taskRepository.select(
                    session.sessionId,
                    candidateId,
                    session.sessionVersion,
                )
                ensureCurrent(current, token)
                showSession(updated)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                failIfCurrent(
                    current,
                    token,
                    exception.toChineseUserMessage("联系人选择已经失效，请重新说"),
                )
            }
        }
    }

    private fun beginRevision(
        session: com.aifriend.contract.model.TaskSession,
        mode: TaskRevisionMode,
        contentOnly: Boolean = false,
        correction: Boolean = false,
    ) {
        val current = generation
        val token = executionToken
        pendingRevisionMode = mode
        mutableUiState.value = TaskUiState(
            stage = TaskStage.PROMPTING_REVISION,
            session = session,
            guardianResumeAvailable = guardianResumeEligible,
            statusMessage = "正在提示，请先听完",
        )
        activeJob = viewModelScope.launch {
            try {
                if (!rehearsalCoordinator.promptTaskRevision(
                        contentOnly = contentOnly,
                        correction = correction,
                        allowAmbiguousCallHint = !contentOnly &&
                            session.understanding?.intent == Intent.HELP,
                    )
                ) {
                    failIfCurrent(current, token, "重说提示没有播放，本次任务不会执行")
                    return@launch
                }
                ensureCurrent(current, token)
                audioCapturePort.startUtterance(TASK_MAX_DURATION_MS)
                ensureCurrent(current, token)
                mutableUiState.value = TaskUiState(
                    stage = TaskStage.RECORDING_REVISION,
                    session = session,
                    guardianResumeAvailable = guardianResumeEligible,
                    statusMessage = if (contentOnly) {
                        "正在听，请只说消息内容"
                    } else {
                        "正在听，不需要再次呼唤小友"
                    },
                )
                revisionTimeoutJob?.cancel()
                revisionTimeoutJob = viewModelScope.launch {
                    delay(TASK_MAX_DURATION_MS.toLong())
                    finishRevision()
                }
                revisionEndpointJob?.cancel()
                revisionEndpointJob = viewModelScope.launch {
                    try {
                        when (audioCapturePort.awaitSpeechEndpoint()) {
                            SpeechEndpointBoundary.NO_SPEECH_TIMEOUT,
                            SpeechEndpointBoundary.UTTERANCE_COMPLETE,
                            SpeechEndpointBoundary.MAXIMUM_REACHED,
                            -> finishRevision()
                            SpeechEndpointBoundary.CONTINUE,
                            SpeechEndpointBoundary.STOPPED,
                            SpeechEndpointBoundary.UNSUPPORTED,
                            -> Unit
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        runCatching { audioCapturePort.cancel() }
                        failIfCurrent(
                            current,
                            token,
                            exception.toChineseUserMessage("本轮录音意外中断，请在当前任务里重新说"),
                        )
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                val failure = exception.toAudioCaptureFailurePresentation(
                    unknownMessage = "本轮语音没有开始，请重新说",
                )
                failIfCurrent(
                    current,
                    token,
                    failure.userMessage,
                    failure.permissionRecoveryRequired,
                )
            }
        }
    }

    fun finishRevision() {
        val state = mutableUiState.value
        val session = state.session ?: return
        val mode = pendingRevisionMode ?: return
        if (state.stage != TaskStage.RECORDING_REVISION) return
        revisionTimeoutJob?.cancel()
        revisionTimeoutJob = null
        revisionEndpointJob?.cancel()
        revisionEndpointJob = null
        pendingRevisionMode = null
        val current = generation
        val token = executionToken
        mutableUiState.value = state.copy(
            stage = TaskStage.SUBMITTING_REVISION,
            statusMessage = "正在结合刚才的任务理解这句话",
            errorMessage = null,
        )
        activeJob = viewModelScope.launch {
            var captured: CapturedAudio? = null
            try {
                captured = audioCapturePort.stop()
                ensureCurrent(current, token)
                submitCapturedRevision(session, captured, mode, current, token)
                captured = null
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                failIfCurrent(
                    current,
                    token,
                    exception.toChineseUserMessage("这句话没有理解成功，请在当前任务里重新说"),
                )
            } finally {
                captured?.clear()
            }
        }
    }

    private suspend fun submitCapturedRevision(
        session: com.aifriend.contract.model.TaskSession,
        captured: CapturedAudio,
        mode: TaskRevisionMode,
        current: Long,
        token: TaskExecutionGate.Token,
    ) {
        try {
            val oldMessage = session.understanding?.messageText
            val audioObjectId = audioUploadRepository.upload(
                AudioPurpose.TASK,
                CreateAudioUploadTicketRequest.MediaType.AUDIO_SLASH_WAV,
                captured.durationMs,
                captured.wavBytes,
            )
            ensureCurrent(current, token)
            val basicExperience = BuildConfig.BASIC_EXPERIENCE_ENABLED &&
                !dialectPackageRegistry.formalPackageAvailable()
            val updated = taskRepository.revise(
                session = session,
                audioObjectId = audioObjectId,
                mode = mode,
                basicRecognitionAudio = captured.takeIf { basicExperience },
            )
            ensureCurrent(current, token)
            val newMessage = updated.understanding?.messageText
            val preserveExistingMessage =
                updated.understanding?.intent == Intent.SEND_MESSAGE &&
                    !oldMessage.isNullOrBlank() && oldMessage == newMessage &&
                    pendingWechatMessageAudio != null &&
                    pendingWechatMessageSessionId == updated.sessionId
            if (!preserveExistingMessage &&
                updated.understanding?.intent == Intent.SEND_MESSAGE &&
                !newMessage.isNullOrBlank() &&
                updated.state in setOf(
                    TaskState.AWAITING_SELECTION,
                    TaskState.AWAITING_CONFIRMATION,
                )
            ) {
                taskAudio?.clear()
                taskAudio = captured
            }
            showSession(updated)
        } finally {
            if (taskAudio !== captured) captured.clear()
        }
    }
    private fun startConfirmation(action: ConfirmationAction) {
        val state = mutableUiState.value
        if (!state.canStartConfirmation(action, rehearsedSummaryHash)) return
        val current = generation
        val token = executionToken
        mutableUiState.value = state.copy(
            stage = TaskStage.RECORDING_CONFIRMATION,
            pendingConfirmationAction = action,
            microphonePermissionRecoveryRequired = false,
            statusMessage = "正在听，请说确认或否认",
            errorMessage = null,
        )
        activeJob = viewModelScope.launch {
            try {
                audioCapturePort.startUtterance(CONFIRMATION_MAX_DURATION_MS)
                ensureCurrent(current, token)
                confirmationTimeoutJob?.cancel()
                confirmationTimeoutJob = viewModelScope.launch {
                    delay(CONFIRMATION_MAX_DURATION_MS.toLong())
                    finishConfirmation()
                }
                confirmationEndpointJob?.cancel()
                confirmationEndpointJob = viewModelScope.launch {
                    try {
                        when (audioCapturePort.awaitSpeechEndpoint()) {
                            SpeechEndpointBoundary.NO_SPEECH_TIMEOUT,
                            SpeechEndpointBoundary.UTTERANCE_COMPLETE,
                            SpeechEndpointBoundary.MAXIMUM_REACHED,
                            -> finishConfirmation()
                            SpeechEndpointBoundary.CONTINUE,
                            SpeechEndpointBoundary.STOPPED,
                            SpeechEndpointBoundary.UNSUPPORTED,
                            -> Unit
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        runCatching { audioCapturePort.cancel() }
                        failIfCurrent(
                            current,
                            token,
                            exception.toChineseUserMessage(
                                "确认录音意外中断，请在当前任务里重新说",
                            ),
                        )
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                val failure = exception.toAudioCaptureFailurePresentation(
                    unknownMessage = "确认监听没有开始，请重新说",
                )
                failIfCurrent(
                    current,
                    token,
                    failure.userMessage,
                    failure.permissionRecoveryRequired,
                )
            }
        }
    }

    /** 复述失败后只重放当前会话，不创建或确认新动作。 */
    fun retryRehearsal() {
        val state = mutableUiState.value
        val session = state.session ?: return
        if (state.stage != TaskStage.REHEARSAL_FAILED ||
            session.state != TaskState.AWAITING_CONFIRMATION
        ) {
            return
        }
        if (taskAudio != null) {
            beginRehearsal(session)
        } else {
            beginSummaryReplay(session)
        }
    }

    /**
     * 老人明确点击后只在本机重新播放当前已冻结的完整复述。
     *
     * 不创建新任务、不上传音频、不提交确认，也不改变联系人、动作或消息正文。
     */
    fun replaySummary() {
        val state = mutableUiState.value
        val session = state.session ?: return
        if (state.stage != TaskStage.ACTIVE ||
            session.state != TaskState.AWAITING_CONFIRMATION ||
            rehearsedSummaryHash.isNullOrBlank() ||
            rehearsedSummaryHash != session.summaryHash
        ) {
            return
        }
        beginSummaryReplay(session)
    }

    fun finishConfirmation() {
        val state = mutableUiState.value
        val session = state.session ?: return
        val expectedAction = state.pendingConfirmationAction ?: return
        if (state.stage != TaskStage.RECORDING_CONFIRMATION) return
        confirmationTimeoutJob?.cancel()
        confirmationTimeoutJob = null
        confirmationEndpointJob?.cancel()
        confirmationEndpointJob = null
        val current = generation
        val token = executionToken
        mutableUiState.value = state.copy(
            stage = TaskStage.MATCHING_CONFIRMATION,
            statusMessage = "正在理解确认或否认",
            errorMessage = null,
        )
        activeJob = viewModelScope.launch {
            try {
                val captured = try {
                    audioCapturePort.stop()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    val failure = exception.toAudioCaptureFailurePresentation(
                        unknownMessage = "录音处理没有完成，请重新录制",
                    )
                    failIfCurrent(
                        current,
                        token,
                        failure.userMessage,
                        failure.permissionRecoveryRequired,
                    )
                    return@launch
                }
                ensureCurrent(current, token)
                confirmationAudio?.clear()
                confirmationAudio = captured
                val decision = localTaskRecognizer.recognizeConfirmation(captured, expectedAction)
                ensureCurrent(current, token)
                if (decision == VoiceConfirmationDecision.UNKNOWN ||
                    decision == VoiceConfirmationDecision.UNAVAILABLE
                ) {
                    val preliminary = try {
                        localTaskRecognizer.recognize(captured)
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Exception) {
                        null
                    }
                    ensureCurrent(current, token)
                    if (preliminary?.isExplicitTaskRevision() == true) {
                        confirmationAudio = null
                        submitCapturedRevision(
                            session,
                            captured,
                            TaskRevisionMode.CORRECTION,
                            current,
                            token,
                        )
                        return@launch
                    }
                }
                captured.clear()
                if (confirmationAudio === captured) confirmationAudio = null
                val route = decision.confirmationRoute()
                Log.i(TASK_CONFIRMATION_TAG, "Confirmation route=" + route.name)
                if (route == VoiceConfirmationRoute.RETRY_LISTENING) {
                    retryConfirmationListening(session, expectedAction, current, token)
                    return@launch
                }
                if (route == VoiceConfirmationRoute.FAIL_UNAVAILABLE) {
                    failIfCurrent(
                        current,
                        token,
                        "本机确认词识别暂时不可用，本次任务不会执行；请重新打开应用后再试",
                    )
                    return@launch
                }
                if (route == VoiceConfirmationRoute.REPEAT_FULL_TASK) {
                    confirmationAttemptCount = 0
                    beginRevision(session = session, mode = TaskRevisionMode.FULL_RETRY)
                    return@launch
                }
                confirmationAttemptCount = 0
                val action = when (route) {
                    VoiceConfirmationRoute.SUBMIT_EXPECTED -> expectedAction
                    VoiceConfirmationRoute.SUBMIT_REJECT -> ConfirmationAction.REJECT
                    VoiceConfirmationRoute.RETRY_LISTENING,
                    VoiceConfirmationRoute.FAIL_UNAVAILABLE,
                    VoiceConfirmationRoute.REPEAT_FULL_TASK,
                    -> error("确认决定路由已在前置分支处理")
                }
                val outcome = taskRepository.confirm(
                    session,
                    action,
                    OffsetDateTime.now(),
                )
                ensureCurrent(current, token)
                confirmationAudio?.clear()
                confirmationAudio = null
                val actionPlan = outcome.actionPlan
                if (actionPlan != null) {
                    if (actionPlan.action == WechatActionType.SEND_AUDIO_AND_TEXT &&
                        (pendingWechatMessageAudio == null ||
                            outcome.session.understanding?.messageText.isNullOrBlank())
                    ) {
                        failAndClear("消息原声或文字不完整，未操作微信")
                        return@launch
                    }
                    pendingWechatActionPlan = actionPlan
                    if (actionPlan.action == WechatActionType.SEND_AUDIO_AND_TEXT) {
                        deliverWechatMessage(
                            session = outcome.session,
                            plan = actionPlan,
                            messageText = checkNotNull(outcome.session.understanding?.messageText),
                            current = current,
                            token = token,
                        )
                        return@launch
                    }
                    val admission = wechatExecutionCoordinator.evaluate(
                        plan = actionPlan,
                        session = outcome.session,
                        taskCurrent = executionGate.canContinue(token),
                    )
                    mutableUiState.value = TaskUiState(
                        stage = TaskStage.COMPLETED,
                        session = outcome.session,
                        guardianResumeAvailable = guardianResumeEligible,
                        statusMessage = when {
                            admission.allowed && actionPlan.action.isWechatCallAction() ->
                                "正在打开微信并按微信号查找亲友"
                            admission.allowed ->
                                "微信动作已通过本机安全准入，正在等待当前页面"
                            else ->
                                "确认已通过，但本次安全检查未通过，未操作微信"
                        },
                    )
                    if (actionPlan.action.isWechatCallAction()) {
                        if (admission.allowed) {
                            if (!wechatLaunchRequestsChannel.trySend(Unit).isSuccess) {
                                closeFailedWechatCall(
                                    plan = actionPlan,
                                    evidenceCode = "WECHAT_LAUNCH_REQUEST_UNAVAILABLE",
                                    message = "没有打开微信，通话没有发起",
                                )
                                return@launch
                            }
                            wechatTransitionTimeoutJob?.cancel()
                            wechatTransitionTimeoutJob = viewModelScope.launch {
                                delay(SEMANTIC_PROFILE_TIMEOUT_MILLIS)
                                wechatExecutionCoordinator.expireSemanticCall(actionPlan.planId)
                            }
                        } else {
                            closeFailedWechatCall(
                                plan = actionPlan,
                                evidenceCode = "SEMANTIC_ADMISSION_${admission.denial?.name ?: "DENIED"}",
                                message = if (
                                    admission.denial ==
                                    WechatExecutionDenial.CALIBRATION_PROFILE_MISSING
                                ) {
                                    "当前手机或微信版本没有校准档案，通话没有发起"
                                } else {
                                    "本次通话未通过安全检查，没有操作微信"
                                },
                            )
                        }
                    }
                } else {
                    showSession(outcome.session)
                    if (outcome.session.state in TERMINAL_STATES) {
                        resumeGuardianAfterTaskIfEligible()
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                failIfCurrent(
                    current,
                    token,
                    exception.toChineseUserMessage("确认没有完成，请重新说确认或否认"),
                )
            }
        }
    }

    /** 未听清确认词时只在当前确认阶段重新提示和监听，不把短词上传成任务纠错。 */
    private suspend fun retryConfirmationListening(
        session: com.aifriend.contract.model.TaskSession,
        expectedAction: ConfirmationAction,
        current: Long,
        token: TaskExecutionGate.Token,
    ) {
        confirmationAttemptCount++
        if (confirmationAttemptCount >= MAXIMUM_CONFIRMATION_ATTEMPTS) {
            failIfCurrent(
                current,
                token,
                "多次没有听清确认或否认，本次任务不会执行",
            )
            return
        }
        mutableUiState.value = TaskUiState(
            stage = TaskStage.REHEARSING,
            session = session,
            pendingConfirmationAction = expectedAction,
            guardianResumeAvailable = guardianResumeEligible,
            statusMessage = "正在提示，请先听完",
        )
        if (!rehearsalCoordinator.promptConfirmationRetry()) {
            failIfCurrent(
                current,
                token,
                "确认重说提示没有播放，本次任务不会执行",
            )
            return
        }
        ensureCurrent(current, token)
        mutableUiState.value = TaskUiState(
            stage = TaskStage.ACTIVE,
            session = session,
            guardianResumeAvailable = guardianResumeEligible,
            statusMessage = "正在听，请说确认或否认",
        )
        startConfirmation(expectedAction)
    }

    fun leave() {
        generation++
        executionGate.invalidate()
        activeJob?.cancel()
        clearContinuationWindow()
        rehearsalCoordinator.close()
        recordingRelease.release()
        viewModelScope.launch {
            rehearsalCoordinator.stop()
        }
        clearAudio()
        clearWechatActionPlan()
        rehearsedSummaryHash = null
        guardianTaskInbox.clear()
        guardianResumeEligible = false
        mutableUiState.value = TaskUiState()
    }

    /**
     * 立即在本机关闭当前任务代次，再异步停止录音和网络工作。
     *
     * 该操作不伪造服务端 `CANCELLED`，但保证迟到响应无法在本机继续动作。
     */
    fun cancelImmediately() {
        val session = mutableUiState.value.session
        executionGate.block(executionToken)
        generation++
        activeJob?.cancel()
        clearContinuationWindow()
        rehearsalCoordinator.close()
        recordingRelease.release()
        activeJob = viewModelScope.launch {
            rehearsalCoordinator.stop()
        }
        clearAudio()
        clearWechatActionPlan()
        rehearsedSummaryHash = null
        guardianTaskInbox.clear()
        showLocalCancellation(
            session,
            "已在本机停止本次任务，不会继续确认或执行",
        )
    }

    /** 守护发起的任务离开可见页面前先停止当前任务，再恢复守护。 */
    suspend fun leaveForGuardianResume(): Boolean {
        val state = mutableUiState.value
        val eligible = state.guardianResumeAvailable
        if (!eligible) return false
        executionGate.block(executionToken)
        generation++
        activeJob?.cancelAndJoin()
        clearContinuationWindow()
        activeJob = null
        rehearsalCoordinator.stop()
        runCatching { audioCapturePort.cancel() }
        clearAudio()
        clearWechatActionPlan()
        rehearsedSummaryHash = null
        guardianTaskInbox.clear()
        guardianResumeEligible = false
        mutableUiState.value = TaskUiState()
        guardianWechatCallAudioCoordinator.resumeIfIdle()
        return true
    }

    private fun startRecording(stage: TaskStage, maximumMs: Int) {
        val current = generation
        val token = executionToken
        mutableUiState.value = mutableUiState.value.copy(
            stage = stage,
            microphonePermissionRecoveryRequired = false,
            statusMessage = if (stage == TaskStage.RECORDING_CONFIRMATION) {
                "正在听，请说确认或否认"
            } else {
                "正在录音，点击停止"
            },
            errorMessage = null,
        )
        activeJob = viewModelScope.launch {
            try {
                audioCapturePort.start(maximumMs)
                ensureCurrent(current, token)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                val failure = exception.toAudioCaptureFailurePresentation()
                failIfCurrent(
                    current,
                    token,
                    failure.userMessage,
                    failure.permissionRecoveryRequired,
                )
            }
        }
    }

    /**
     * 接收真实渠道上报后由服务端返回的终态会话。
     *
     * 本方法不接受本地推测结果；未来微信执行器只能把 reportTaskChannelResult 的响应传入。
     */
    fun acceptVerifiedChannelResult(
        session: com.aifriend.contract.model.TaskSession,
        resumeGuardian: Boolean = true,
    ) {
        if (mutableUiState.value.session?.sessionId != session.sessionId) return
        clearContinuationWindow()
        showSession(session)
        if (resumeGuardian) resumeGuardianAfterTaskIfEligible()
        val continuation = continuationWindow.open(session) ?: return
        mutableUiState.value = mutableUiState.value.copy(
            continuation = continuation,
            statusMessage = continuationStatus(session.state, continuation),
        )
        continuationSpeechJob = viewModelScope.launch {
            continuationAnnouncer.announceAvailable(continuation.contactLabel)
        }
        continuationTimerJob = viewModelScope.launch {
            while (true) {
                delay(CONTINUATION_TICK_MILLIS)
                val snapshot = continuationWindow.snapshot()
                if (snapshot == null) {
                    continuationSpeechJob?.cancelAndJoin()
                    continuationSpeechJob = null
                    continuationAnnouncer.close()
                    mutableUiState.value = mutableUiState.value.copy(
                        continuation = null,
                        statusMessage = "主人，我休息了；已清除上一位联系人",
                    )
                    continuationSpeechJob = viewModelScope.launch {
                        continuationAnnouncer.announceSleeping()
                    }
                    return@launch
                }
                mutableUiState.value = mutableUiState.value.copy(
                    continuation = snapshot,
                    statusMessage = continuationStatus(session.state, snapshot),
                )
            }
        }
    }

    /** 明确消费仍有效的五秒窗口，并以全新消息录音继续。 */
    fun continueMessage() {
        val previousContactId = continuationWindow.consume() ?: return
        clearContinuationJobs()
        pendingPreviousConfirmedContactId = previousContactId
        startFreshTaskRecording(previousConfirmedContactId = previousContactId)
    }

    private fun startFreshTaskRecording(previousConfirmedContactId: String?) {
        val previousJob = activeJob
        generation++
        executionToken = executionGate.open()
        clearContinuationWindow()
        pendingPreviousConfirmedContactId = previousConfirmedContactId
        previousJob?.cancel()
        rehearsalCoordinator.close()
        clearAudio()
        clearWechatActionPlan()
        rehearsedSummaryHash = null
        val current = generation
        val token = executionToken
        mutableUiState.value = TaskUiState(
            stage = TaskStage.RECORDING_TASK,
            guardianResumeAvailable = guardianResumeEligible,
            statusMessage = "正在录音，点击停止",
        )
        activeJob = viewModelScope.launch {
            try {
                rehearsalCoordinator.stop()
                previousJob?.cancelAndJoin()
                // 旧页面/上一轮可能仍持有已结束的录音；释放完毕才允许新一轮采集。
                recordingRelease.release().await()
                audioCapturePort.start(TASK_MAX_DURATION_MS)
                ensureCurrent(current, token)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                val failure = exception.toAudioCaptureFailurePresentation()
                failIfCurrent(
                    current,
                    token,
                    failure.userMessage,
                    failure.permissionRecoveryRequired,
                )
            }
        }
    }

    private fun showSession(session: com.aifriend.contract.model.TaskSession) {
        clearContinuationWindow()
        val canReuseMessageAudio = taskAudio == null &&
            session.understanding?.intent == Intent.SEND_MESSAGE &&
            pendingWechatMessageAudio != null &&
            pendingWechatMessageSessionId == session.sessionId
        clearWechatActionPlan(preserveMessageAudio = canReuseMessageAudio)
        rehearsedSummaryHash = null
        if (session.state == TaskState.AWAITING_CONFIRMATION) {
            confirmationAttemptCount = 0
            if (canReuseMessageAudio) beginSummaryReplay(session) else beginRehearsal(session)
            return
        }
        if (session.state == TaskState.AWAITING_SELECTION) {
            candidateSelectionAttemptCount = 0
            beginCandidateSelection(session)
            return
        }
        if (session.state == TaskState.NEEDS_CONTENT_REPEAT) {
            beginRevision(
                session = session,
                mode = TaskRevisionMode.CORRECTION,
                contentOnly = true,
            )
            return
        }
        if (session.state == TaskState.NEEDS_RETRY) {
            beginRevision(session = session, mode = TaskRevisionMode.FULL_RETRY)
            return
        }
        clearAudio()
        mutableUiState.value = TaskUiState(
            stage = when {
                session.state == TaskState.CANCELLED -> TaskStage.CANCELLED
                session.state in TERMINAL_STATES -> TaskStage.COMPLETED
                else -> TaskStage.ACTIVE
            },
            session = session,
            guardianResumeAvailable = guardianResumeEligible,
            statusMessage = session.state.userMessage(),
        )
    }

    private fun beginCandidateSelection(
        session: com.aifriend.contract.model.TaskSession,
        retry: Boolean = false,
    ) {
        val candidates = session.candidates.orEmpty().take(3)
        if (candidates.isEmpty() ||
            AllowedAction.SELECT_CANDIDATE !in session.allowedActions
        ) {
            failAndClear("没有可安全选择的联系人，本次任务不会执行")
            return
        }
        val current = generation
        val token = executionToken
        mutableUiState.value = TaskUiState(
            stage = TaskStage.PROMPTING_SELECTION,
            session = session,
            guardianResumeAvailable = guardianResumeEligible,
            statusMessage = "正在逐个播报可能的联系人，请先听完",
        )
        activeJob = viewModelScope.launch {
            try {
                val labels = candidates.map { candidate ->
                    candidate.contact.displayName.ifBlank { candidate.contact.alias }
                }
                if (!rehearsalCoordinator.promptCandidateSelection(labels, retry)) {
                    failIfCurrent(current, token, "联系人候选没有播放，本次任务不会执行")
                    return@launch
                }
                ensureCurrent(current, token)
                audioCapturePort.start(CANDIDATE_SELECTION_MAX_DURATION_MS)
                ensureCurrent(current, token)
                mutableUiState.value = TaskUiState(
                    stage = TaskStage.RECORDING_SELECTION,
                    session = session,
                    guardianResumeAvailable = guardianResumeEligible,
                    statusMessage = "正在听，请直接说称呼或第几个",
                )
                candidateSelectionTimeoutJob?.cancel()
                candidateSelectionTimeoutJob = viewModelScope.launch {
                    delay(CANDIDATE_SELECTION_MAX_DURATION_MS.toLong())
                    finishCandidateSelection()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                val failure = exception.toAudioCaptureFailurePresentation(
                    unknownMessage = "联系人语音选择没有开始，请点击候选联系人",
                )
                if (current == generation && executionGate.canContinue(token)) {
                    mutableUiState.value = TaskUiState(
                        stage = TaskStage.ACTIVE,
                        session = session,
                        guardianResumeAvailable = guardianResumeEligible,
                        microphonePermissionRecoveryRequired =
                            failure.permissionRecoveryRequired,
                        statusMessage = "语音选择暂时不可用，请点击联系人",
                        errorMessage = failure.userMessage,
                    )
                }
            }
        }
    }

    fun finishCandidateSelection() {
        val state = mutableUiState.value
        val session = state.session ?: return
        if (state.stage != TaskStage.RECORDING_SELECTION ||
            session.state != TaskState.AWAITING_SELECTION
        ) return
        candidateSelectionTimeoutJob?.cancel()
        candidateSelectionTimeoutJob = null
        val current = generation
        val token = executionToken
        mutableUiState.value = state.copy(
            stage = TaskStage.MATCHING_SELECTION,
            statusMessage = "正在理解您选择的联系人",
            errorMessage = null,
        )
        activeJob = viewModelScope.launch {
            var captured: CapturedAudio? = null
            try {
                captured = audioCapturePort.stop()
                ensureCurrent(current, token)
                val selection = localTaskRecognizer.recognizeCandidateSelection(
                    captured,
                    session.candidates.orEmpty().take(3),
                )
                captured.clear()
                captured = null
                ensureCurrent(current, token)
                when (selection) {
                    is VoiceCandidateSelection.Selected -> {
                        val updated = taskRepository.select(
                            session.sessionId,
                            selection.candidateId,
                            session.sessionVersion,
                        )
                        ensureCurrent(current, token)
                        candidateSelectionAttemptCount = 0
                        showSession(updated)
                    }
                    VoiceCandidateSelection.REPEAT -> {
                        candidateSelectionAttemptCount++
                        beginCandidateSelection(session, retry = true)
                    }
                    VoiceCandidateSelection.CANCEL -> {
                        showLocalCancellation(
                            session,
                            "已按口头取消停止本次任务，不会执行",
                        )
                    }
                    VoiceCandidateSelection.UNKNOWN -> {
                        candidateSelectionAttemptCount++
                        if (candidateSelectionAttemptCount < MAXIMUM_CANDIDATE_SELECTION_ATTEMPTS) {
                            beginCandidateSelection(session, retry = true)
                        } else {
                            mutableUiState.value = TaskUiState(
                                stage = TaskStage.ACTIVE,
                                session = session,
                                guardianResumeAvailable = guardianResumeEligible,
                                statusMessage = "连续没有听清，可直接点击一位联系人或取消",
                                errorMessage = "没有唯一听清口头选择，本次尚未执行",
                            )
                        }
                    }
                    VoiceCandidateSelection.UNAVAILABLE -> {
                        mutableUiState.value = TaskUiState(
                            stage = TaskStage.ACTIVE,
                            session = session,
                            guardianResumeAvailable = guardianResumeEligible,
                            statusMessage = "语音选择暂时不可用，可直接点击一位联系人或取消",
                            errorMessage = "本机联系人选择识别不可用，本次尚未执行",
                        )
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (current == generation && executionGate.canContinue(token)) {
                    mutableUiState.value = TaskUiState(
                        stage = TaskStage.ACTIVE,
                        session = session,
                        guardianResumeAvailable = guardianResumeEligible,
                        statusMessage = "语音选择没有完成，可直接点击一位联系人或取消",
                        errorMessage = exception.toChineseUserMessage(
                            "联系人选择没有完成，本次尚未执行",
                        ),
                    )
                }
            } finally {
                captured?.clear()
            }
        }
    }

    private fun requireConfirmationAction(
        session: com.aifriend.contract.model.TaskSession,
    ): ConfirmationAction = when {
        AllowedAction.CONFIRM_SEND in session.allowedActions -> ConfirmationAction.CONFIRM_SEND
        AllowedAction.CONFIRM_CALL in session.allowedActions -> ConfirmationAction.CONFIRM_CALL
        else -> error("当前任务没有可确认动作，本次不会执行")
    }

    private fun beginRehearsal(session: com.aifriend.contract.model.TaskSession) {
        val understanding = session.understanding
        val summary = session.spokenSummary
        val summaryHash = session.summaryHash
        if (understanding == null || summary.isNullOrBlank() || summaryHash.isNullOrBlank()) {
            showRehearsalFailure(session, "完整复述信息不完整，不能确认")
            return
        }
        if (understanding.intent == com.aifriend.contract.model.Intent.SEND_MESSAGE &&
            understanding.messageText.isNullOrBlank()
        ) {
            showRehearsalFailure(session, "消息文字不完整，不能确认")
            return
        }
        val current = generation
        val token = executionToken
        rehearsedSummaryHash = null
        mutableUiState.value = TaskUiState(
            stage = TaskStage.REHEARSING,
            session = session,
            guardianResumeAvailable = guardianResumeEligible,
            statusMessage = "正在播放最终原声和完整复述，请先听完",
        )
        activeJob = viewModelScope.launch {
            var retainedMessageAudio: CapturedAudio? = null
            try {
                retainedMessageAudio = rehearsalCoordinator.rehearse(
                    source = taskAudio,
                    intent = understanding.intent,
                    ranges = understanding.effectiveAudioRanges,
                    spokenSummary = summary,
                )
                ensureCurrent(current, token)
                taskAudio?.clear()
                taskAudio = null
                pendingWechatMessageAudio?.clear()
                pendingWechatMessageAudio = retainedMessageAudio
                pendingWechatMessageSessionId = session.sessionId
                retainedMessageAudio = null
                rehearsedSummaryHash = summaryHash
                mutableUiState.value = TaskUiState(
                    stage = TaskStage.ACTIVE,
                    session = session,
                    guardianResumeAvailable = guardianResumeEligible,
                    statusMessage = "正在听，请说确认或否认",
                )
                startConfirmation(requireConfirmationAction(session))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (current == generation && executionGate.canContinue(token)) {
                    showRehearsalFailure(
                        session,
                        exception.toAudioPlaybackUserMessage(
                            AudioPlaybackContent.TASK_REHEARSAL,
                        ),
                    )
                }
            } finally {
                retainedMessageAudio?.clear()
            }
        }
    }

    private fun showRehearsalFailure(
        session: com.aifriend.contract.model.TaskSession,
        message: String,
    ) {
        rehearsedSummaryHash = null
        mutableUiState.value = TaskUiState(
            stage = TaskStage.REHEARSAL_FAILED,
            session = session,
            guardianResumeAvailable = guardianResumeEligible,
            statusMessage = "完整复述没有播放完，不能确认",
            errorMessage = message,
        )
    }

    private fun showLocalCancellation(session: com.aifriend.contract.model.TaskSession?, message: String) {
        rehearsedSummaryHash = null
        clearAudio()
        clearWechatActionPlan()
        mutableUiState.value = TaskUiState(
            stage = TaskStage.CANCELLED,
            session = session,
            guardianResumeAvailable = guardianResumeEligible,
            statusMessage = message,
        )
        resumeGuardianAfterTaskIfEligible()
    }

    private fun failAndClear(
        message: String,
        microphonePermissionRecoveryRequired: Boolean = false,
    ) {
        executionGate.block(executionToken)
        recordingRelease.release()
        rehearsedSummaryHash = null
        clearAudio()
        clearWechatActionPlan()
        clearContinuationWindow()
        mutableUiState.value = TaskUiState(
            stage = TaskStage.FAILED,
            guardianResumeAvailable = guardianResumeEligible,
            microphonePermissionRecoveryRequired = microphonePermissionRecoveryRequired,
            statusMessage = "本次任务已停止，不会自动补执行",
            errorMessage = message,
        )
        resumeGuardianAfterTaskIfEligible()
    }

    private fun resumeGuardianAfterTaskIfEligible() {
        if (!guardianResumeEligible) return
        guardianResumeEligible = false
        mutableUiState.value = mutableUiState.value.copy(guardianResumeAvailable = false)
        viewModelScope.launch { guardianWechatCallAudioCoordinator.resumeIfIdle() }
    }

    private fun failIfCurrent(
        expected: Long,
        token: TaskExecutionGate.Token,
        message: String,
        microphonePermissionRecoveryRequired: Boolean = false,
    ) {
        if (expected == generation && executionGate.canContinue(token)) {
            failAndClear(message, microphonePermissionRecoveryRequired)
        }
    }

    private fun clearAudio() {
        confirmationTimeoutJob?.cancel()
        confirmationTimeoutJob = null
        confirmationEndpointJob?.cancel()
        confirmationEndpointJob = null
        candidateSelectionTimeoutJob?.cancel()
        candidateSelectionTimeoutJob = null
        revisionTimeoutJob?.cancel()
        revisionTimeoutJob = null
        revisionEndpointJob?.cancel()
        revisionEndpointJob = null
        pendingRevisionMode = null
        taskAudio?.clear()
        confirmationAudio?.clear()
        taskAudio = null
        confirmationAudio = null
    }

    /** 动作计划只驻留当前 ViewModel；退出、失败、取消或状态切换时立即丢弃。 */
    private fun clearWechatActionPlan(preserveMessageAudio: Boolean = false) {
        wechatTransitionTimeoutJob?.cancel()
        wechatTransitionTimeoutJob = null
        pendingWechatActionPlan = null
        if (!preserveMessageAudio) {
            pendingWechatMessageAudio?.clear()
            pendingWechatMessageAudio = null
            pendingWechatMessageSessionId = null
        }
        wechatExecutionCoordinator.clear()
    }

    private fun beginSummaryReplay(session: com.aifriend.contract.model.TaskSession) {
        val understanding = session.understanding
        val summary = session.spokenSummary
        val summaryHash = session.summaryHash
        if (understanding == null || summary.isNullOrBlank() || summaryHash.isNullOrBlank()) {
            showRehearsalFailure(session, "完整复述信息不完整，不能确认")
            return
        }
        val current = generation
        val token = executionToken
        rehearsedSummaryHash = null
        mutableUiState.value = TaskUiState(
            stage = TaskStage.REHEARSING,
            session = session,
            guardianResumeAvailable = guardianResumeEligible,
            statusMessage = "正在重新播放完整复述，请先听完",
        )
        activeJob = viewModelScope.launch {
            try {
                rehearsalCoordinator.replay(
                    retainedMessageAudio = pendingWechatMessageAudio,
                    intent = understanding.intent,
                    spokenSummary = summary,
                )
                ensureCurrent(current, token)
                rehearsedSummaryHash = summaryHash
                mutableUiState.value = TaskUiState(
                    stage = TaskStage.ACTIVE,
                    session = session,
                    guardianResumeAvailable = guardianResumeEligible,
                    statusMessage = "正在听，请说确认或否认",
                )
                startConfirmation(requireConfirmationAction(session))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (current == generation && executionGate.canContinue(token)) {
                    showRehearsalFailure(
                        session,
                        exception.toAudioPlaybackUserMessage(
                            AudioPlaybackContent.TASK_REHEARSAL,
                        ),
                    )
                }
            }
        }
    }

    /** 应用根层无法把本次通话交给微信时，立即结束当前计划且不自动重试。 */
    fun reportWechatLaunchFailed() {
        val plan = pendingWechatActionPlan ?: return
        if (!plan.action.isWechatCallAction()) return
        closeFailedWechatCall(
            plan = plan,
            evidenceCode = "WECHAT_APP_LAUNCH_FAILED",
            message = "没有打开微信，通话没有发起",
        )
    }

    private fun handlePublishedWechatPage(planId: String) {
        val plan = pendingWechatActionPlan ?: return
        val state = mutableUiState.value
        val session = state.session ?: return
        if (plan.planId != planId || !executionGate.canContinue(executionToken) ||
            session.state != TaskState.EXECUTING
        ) {
            return
        }
        val admission = wechatExecutionCoordinator.evaluate(
            plan = plan,
            session = session,
            taskCurrent = true,
        )
        mutableUiState.value = state.copy(
            statusMessage = when {
                admission.allowed -> when (plan.action) {
                    WechatActionType.SEND_AUDIO_AND_TEXT ->
                        "联系人资料页已再次核对，正在单次请求打开聊天页"
                    WechatActionType.START_VOICE_CALL,
                    WechatActionType.START_VIDEO_CALL,
                    -> "联系人资料页已再次核对，正在单次请求打开通话选择页"
                }
                admission.denial == WechatExecutionDenial.PAGE_SNAPSHOT_MISSING ->
                    "正在等待微信联系人资料页；未发送任何内容"
                else -> "联系人资料页未通过安全复核，未操作微信"
            },
        )
    }

    private fun handleWechatActionOutcome(outcome: WechatVerifiedContactProfileActionOutcome) {
        val plan = pendingWechatActionPlan ?: return
        if (plan.planId != outcome.planId || !executionGate.canContinue(executionToken)) return
        if (outcome.status == WechatVerifiedContactProfileActionStatus.CLICK_REQUEST_ACCEPTED) {
            mutableUiState.value = mutableUiState.value.copy(
                statusMessage = when (plan.action) {
                    WechatActionType.SEND_AUDIO_AND_TEXT ->
                        "已请求打开微信聊天页，尚未发送语音或文字"
                    WechatActionType.START_VOICE_CALL,
                    WechatActionType.START_VIDEO_CALL,
                    -> "已请求打开微信通话选择页，尚未发起通话"
                },
            )
            wechatTransitionTimeoutJob?.cancel()
            wechatTransitionTimeoutJob = viewModelScope.launch {
                delay(DIRECT_CHAT_TRANSITION_TIMEOUT_MILLIS)
                wechatExecutionCoordinator.expireDirectChatTransition(outcome.planId)
            }
            return
        }
        if (plan.action != WechatActionType.SEND_AUDIO_AND_TEXT) {
            closeFailedWechatCall(
                plan,
                evidenceCode = "CALL_PROFILE_ACTION_${outcome.status.name}",
                message = "没有安全打开微信通话选择页，通话没有发起",
            )
            return
        }
        clearWechatActionPlan()
        mutableUiState.value = mutableUiState.value.copy(
            statusMessage = when (outcome.status) {
                WechatVerifiedContactProfileActionStatus.SERVICE_UNAVAILABLE ->
                    "微信辅助服务未连接，未打开聊天页，也未发送内容"
                else -> "没有安全打开微信聊天页，未发送任何内容"
            },
        )
    }

    private fun handleDirectChatTransitionOutcome(outcome: WechatDirectChatTransitionOutcome) {
        val plan = pendingWechatActionPlan ?: return
        if (plan.planId != outcome.planId || !executionGate.canContinue(executionToken)) return
        wechatTransitionTimeoutJob?.cancel()
        wechatTransitionTimeoutJob = null
        if (outcome.status == WechatDirectChatTransitionStatus.CONFIRMED) {
            if (plan.action == WechatActionType.SEND_AUDIO_AND_TEXT) {
                closeUnsupportedSpecifiedContactDelivery(plan)
            } else {
                mutableUiState.value = mutableUiState.value.copy(
                    statusMessage = if (plan.action == WechatActionType.START_VOICE_CALL) {
                        "已确认微信语音通话选择页，正在单次请求语音通话"
                    } else {
                        "已确认微信视频通话选择页，正在单次请求视频通话"
                    },
                )
            }
            return
        }
        if (plan.action != WechatActionType.SEND_AUDIO_AND_TEXT) {
            closeFailedWechatCall(
                plan,
                evidenceCode = "CALL_CHOICE_PAGE_${outcome.status.name}",
                message = "没有安全确认微信通话选择页，通话没有发起",
            )
            return
        }
        clearWechatActionPlan()
        mutableUiState.value = mutableUiState.value.copy(
            statusMessage = when (outcome.status) {
                WechatDirectChatTransitionStatus.SERVICE_UNAVAILABLE ->
                    "微信辅助服务已中断，未确认聊天页，也未发送内容"
                else -> "未能安全确认目标聊天页，未发送任何内容"
            },
        )
    }

    private fun handleCallChoiceActionOutcome(outcome: WechatCallChoiceActionOutcome) {
        val plan = pendingWechatActionPlan ?: return
        val session = mutableUiState.value.session ?: return
        if (plan.planId != outcome.planId || plan.action != outcome.action ||
            session.state != TaskState.EXECUTING ||
            !executionGate.canContinue(executionToken)
        ) {
            return
        }
        val current = generation
        val token = executionToken
        val report = outcome.toDeliveryReport()
        val accepted = outcome.status == WechatCallChoiceActionStatus.CLICK_REQUEST_ACCEPTED
        val callName = if (plan.action == WechatActionType.START_VOICE_CALL) {
            "语音通话"
        } else {
            "视频通话"
        }
        if (accepted && outcome.callStartedConfirmationArmed) {
            mutableUiState.value = mutableUiState.value.copy(
                statusMessage = "已请求微信打开${callName}，正在确认通话页面",
            )
            wechatTransitionTimeoutJob?.cancel()
            wechatTransitionTimeoutJob = viewModelScope.launch {
                delay(CALL_STARTED_TRANSITION_TIMEOUT_MILLIS)
                wechatExecutionCoordinator.expireCallStartedTransition(outcome.planId)
            }
            return
        }
        clearWechatActionPlan()
        mutableUiState.value = mutableUiState.value.copy(
            statusMessage = if (accepted) {
                "已请求微信打开${callName}，尚未确认通话已开始"
            } else {
                "没有安全打开${callName}，不会自动重试"
            },
        )
        activeJob = viewModelScope.launch {
            try {
                val terminalSession = taskRepository.reportChannelResult(
                    sessionId = session.sessionId,
                    plan = plan,
                    result = report.result,
                    parts = report.parts,
                    occurredAt = OffsetDateTime.now(),
                )
                ensureCurrent(current, token)
                acceptVerifiedChannelResult(terminalSession)
                mutableUiState.value = mutableUiState.value.copy(
                    statusMessage = if (accepted) {
                        "已请求微信打开${callName}；请在微信中查看，尚未确认通话已开始"
                    } else {
                        "${callName}没有发起，本次任务已安全结束"
                    },
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                failIfCurrent(
                    current,
                    token,
                    "${callName}操作结果未能保存，请不要重复操作",
                )
            }
        }
    }

    private fun handleCallStartedTransitionOutcome(
        outcome: WechatCallStartedTransitionOutcome,
    ) {
        val plan = pendingWechatActionPlan ?: return
        val session = mutableUiState.value.session ?: return
        if (plan.planId != outcome.planId || plan.action != outcome.action ||
            session.state != TaskState.EXECUTING ||
            !executionGate.canContinue(executionToken)
        ) {
            return
        }
        wechatTransitionTimeoutJob?.cancel()
        wechatTransitionTimeoutJob = null
        val current = generation
        val token = executionToken
        val report = outcome.toDeliveryReport()
        val confirmed = outcome.status == WechatCallStartedTransitionStatus.CONFIRMED
        val callName = if (plan.action == WechatActionType.START_VOICE_CALL) {
            "语音通话"
        } else {
            "视频通话"
        }
        clearWechatActionPlan()
        mutableUiState.value = mutableUiState.value.copy(
            statusMessage = if (confirmed) {
                "已确认微信进入${callName}页面"
            } else {
                "已请求微信打开${callName}，但未确认通话已开始"
            },
        )
        activeJob = viewModelScope.launch {
            try {
                val terminalSession = taskRepository.reportChannelResult(
                    sessionId = session.sessionId,
                    plan = plan,
                    result = report.result,
                    parts = report.parts,
                    occurredAt = OffsetDateTime.now(),
                )
                ensureCurrent(current, token)
                acceptVerifiedChannelResult(terminalSession, resumeGuardian = !confirmed)
                mutableUiState.value = mutableUiState.value.copy(
                    statusMessage = if (confirmed) {
                        "已确认微信进入${callName}页面；不代表对方已接听"
                    } else {
                        "已交给微信处理；未确认${callName}已经开始"
                    },
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                failIfCurrent(
                    current,
                    token,
                    "${callName}操作结果未能保存，请不要重复操作",
                )
            }
        }
    }

    /**
     * 零开发者采样的 MVP 通话结果。
     *
     * 第一阶段只表示已打开通话类型选择页；最终点击被系统接受也只报告交给微信，
     * 不继续观察通话页面，更不推导 CALL_STARTED。
     */
    private fun handleSemanticCallOutcome(outcome: WechatSemanticCallExecutionOutcome) {
        val plan = pendingWechatActionPlan ?: return
        val session = mutableUiState.value.session ?: return
        if (plan.planId != outcome.planId || plan.action != outcome.action ||
            session.state != TaskState.EXECUTING ||
            !executionGate.canContinue(executionToken)
        ) {
            return
        }
        wechatTransitionTimeoutJob?.cancel()
        wechatTransitionTimeoutJob = null
        if (outcome.status == WechatSemanticCallExecutionStatus.AWAITING_CALL_CHOICE) {
            mutableUiState.value = mutableUiState.value.copy(
                statusMessage = if (plan.action == WechatActionType.START_VOICE_CALL) {
                    "已核对亲友资料，正在安全选择语音通话"
                } else {
                    "已核对亲友资料，正在安全选择视频通话"
                },
            )
            wechatTransitionTimeoutJob = viewModelScope.launch {
                delay(SEMANTIC_CHOICE_TIMEOUT_MILLIS)
                wechatExecutionCoordinator.expireSemanticCall(outcome.planId)
            }
            return
        }
        if (outcome.status == WechatSemanticCallExecutionStatus.AWAITING_CALL_STARTED) {
            mutableUiState.value = mutableUiState.value.copy(
                statusMessage = if (plan.action == WechatActionType.START_VOICE_CALL) {
                    "已请求微信打开语音通话，正在确认通话页面"
                } else {
                    "已请求微信打开视频通话，正在确认通话页面"
                },
            )
            wechatTransitionTimeoutJob = viewModelScope.launch {
                delay(CALL_STARTED_TRANSITION_TIMEOUT_MILLIS)
                wechatExecutionCoordinator.expireCallStartedTransition(outcome.planId)
            }
            return
        }

        val report = outcome.finalDeliveryReportOrNull() ?: return
        val accepted = outcome.status == WechatSemanticCallExecutionStatus.HANDED_TO_WECHAT
        val callName = if (plan.action == WechatActionType.START_VOICE_CALL) {
            "语音通话"
        } else {
            "视频通话"
        }
        val failureMessage = outcome.status.wechatCallFailureMessage(callName)
        val current = generation
        val token = executionToken
        clearWechatActionPlan()
        mutableUiState.value = mutableUiState.value.copy(
            statusMessage = if (accepted) {
                "已交给微信处理；请在微信中查看，未确认通话已开始"
            } else {
                failureMessage
            },
        )
        activeJob = viewModelScope.launch {
            try {
                val terminalSession = taskRepository.reportChannelResult(
                    sessionId = session.sessionId,
                    plan = plan,
                    result = report.result,
                    parts = report.parts,
                    occurredAt = OffsetDateTime.now(),
                )
                ensureCurrent(current, token)
                acceptVerifiedChannelResult(terminalSession)
                mutableUiState.value = mutableUiState.value.copy(
                    statusMessage = if (accepted) {
                        "已交给微信处理；请在微信中查看，未确认通话已开始"
                    } else {
                        failureMessage
                    },
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                failIfCurrent(
                    current,
                    token,
                    "${callName}操作结果未能保存，请不要重复操作",
                )
            }
        }
    }

    private fun closeFailedWechatCall(
        plan: com.aifriend.contract.model.WechatActionPlan,
        evidenceCode: String,
        message: String,
    ) {
        val session = mutableUiState.value.session
        if (session == null || session.state != TaskState.EXECUTING) {
            clearWechatActionPlan()
            mutableUiState.value = mutableUiState.value.copy(statusMessage = message)
            return
        }
        val current = generation
        val token = executionToken
        val report = failedWechatCallDeliveryReport(plan.action, evidenceCode)
        clearWechatActionPlan()
        mutableUiState.value = mutableUiState.value.copy(statusMessage = message)
        activeJob = viewModelScope.launch {
            try {
                val terminalSession = taskRepository.reportChannelResult(
                    sessionId = session.sessionId,
                    plan = plan,
                    result = report.result,
                    parts = report.parts,
                    occurredAt = OffsetDateTime.now(),
                )
                ensureCurrent(current, token)
                acceptVerifiedChannelResult(terminalSession)
                mutableUiState.value = mutableUiState.value.copy(statusMessage = message)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                failIfCurrent(
                    current,
                    token,
                    "$message；结果未能保存，请不要重复操作",
                )
            }
        }
    }

    /**
     * 当前 Open SDK 动作计划不能构造指定联系人交付请求，因此在聊天页确认后失败关闭。
     *
     * 原声在联网前立即清除；只上报 AUDIO/UNSUPPORTED，不尝试文字、不自动重试，
     * 更不能退化为可能选错联系人的普通微信分享。
     */
    private fun closeUnsupportedSpecifiedContactDelivery(
        plan: com.aifriend.contract.model.WechatActionPlan,
    ) {
        val session = mutableUiState.value.session
        if (session == null || session.state != TaskState.EXECUTING) {
            clearWechatActionPlan()
            mutableUiState.value = mutableUiState.value.copy(
                statusMessage = "当前任务状态已经变化，未发送任何内容",
            )
            return
        }
        val current = generation
        val token = executionToken
        val report = unsupportedWechatMessageDeliveryReport(
            evidenceCode = "LEGACY_SPECIFIED_CONTACT_DELIVERY_UNAVAILABLE",
        )
        clearWechatActionPlan()
        mutableUiState.value = mutableUiState.value.copy(
            statusMessage = "已安全打开目标聊天页，但当前版本无法定向交付原声；未发送任何内容",
        )
        activeJob = viewModelScope.launch {
            try {
                val terminalSession = taskRepository.reportChannelResult(
                    sessionId = session.sessionId,
                    plan = plan,
                    result = report.result,
                    parts = report.parts,
                    occurredAt = OffsetDateTime.now(),
                )
                ensureCurrent(current, token)
                acceptVerifiedChannelResult(terminalSession)
                mutableUiState.value = mutableUiState.value.copy(
                    statusMessage =
                        "当前版本无法定向交付原声，本次任务已安全结束；未发送任何内容",
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                failIfCurrent(
                    current,
                    token,
                    "当前版本无法定向交付原声，未发送任何内容；结果未能保存，请不要重复操作",
                )
            }
        }
    }

    /** 当前签名计划中先交给微信原声，只有原声回调成功才继续文字。 */
    private suspend fun deliverWechatMessage(
        session: com.aifriend.contract.model.TaskSession,
        plan: com.aifriend.contract.model.WechatActionPlan,
        messageText: String,
        current: Long,
        token: TaskExecutionGate.Token,
    ) {
        val audio = pendingWechatMessageAudio
        val port = wechatMessageHandoffPortFactory.create(plan)
        val report = if (audio == null || port == null) {
            unsupportedWechatMessageDeliveryReport(
                evidenceCode = "CALIBRATED_MESSAGE_SHARE_UNAVAILABLE",
            )
        } else {
            mutableUiState.value = mutableUiState.value.copy(
                statusMessage = "正在通过微信把原声交给这位亲友",
            )
            wechatMessageDeliveryCoordinator.deliver(audio, messageText, port)
        }
        ensureCurrent(current, token)
        clearWechatActionPlan()
        mutableUiState.value = mutableUiState.value.copy(
            statusMessage = when (report.result) {
                com.aifriend.contract.model.ChannelResult.HANDED_TO_WECHAT ->
                    "原声和文字已交给微信"
                com.aifriend.contract.model.ChannelResult.PARTIAL ->
                    "原声已交给微信，文字没有完成；不会重发原声"
                com.aifriend.contract.model.ChannelResult.UNSUPPORTED ->
                    "当前手机还没有消息发送校准，未发送任何内容"
                else -> "本次微信交付没有完成，不会自动重试"
            },
        )
        try {
            val terminalSession = taskRepository.reportChannelResult(
                sessionId = session.sessionId,
                plan = plan,
                result = report.result,
                parts = report.parts,
                occurredAt = OffsetDateTime.now(),
            )
            ensureCurrent(current, token)
            acceptVerifiedChannelResult(terminalSession)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            failIfCurrent(
                current,
                token,
                "微信操作结果未能保存，请不要重复发送",
            )
        }
    }

    private fun clearContinuationWindow() {
        continuationWindow.clear()
        clearContinuationJobs()
        pendingPreviousConfirmedContactId = null
    }

    private fun clearContinuationJobs() {
        continuationTimerJob?.cancel()
        continuationSpeechJob?.cancel()
        continuationTimerJob = null
        continuationSpeechJob = null
        continuationAnnouncer.close()
    }

    private fun continuationStatus(
        state: TaskState,
        continuation: MessageContinuationUi,
    ): String = if (state == TaskState.PARTIAL) {
        "原声已交给微信，文字失败；${continuation.secondsRemaining} 秒内可继续给${continuation.contactLabel}说话"
    } else {
        "已交给微信；${continuation.secondsRemaining} 秒内可继续给${continuation.contactLabel}说话"
    }

    private fun ensureCurrent(expected: Long, token: TaskExecutionGate.Token) {
        check(expected == generation && executionGate.canContinue(token)) {
            "任务页面已退出或本次任务已取消"
        }
    }

    override fun onCleared() {
        executionGate.invalidate()
        activeJob?.cancel()
        recordingRelease.release()
        clearContinuationWindow()
        rehearsalCoordinator.close()
        hapticFeedbackPort.cancel()
        clearAudio()
        clearWechatActionPlan()
        guardianTaskInbox.clear()
        super.onCleared()
    }

    private companion object {
        const val TASK_MAX_DURATION_MS = 60_000
        const val TASK_AUDIO_POLICY_VERSION = "task-audio-v1"
        const val CONFIRMATION_MAX_DURATION_MS = 5_000
        const val MAXIMUM_CONFIRMATION_ATTEMPTS = 8
        const val MAXIMUM_CANDIDATE_SELECTION_ATTEMPTS = 5
        const val CANDIDATE_SELECTION_MAX_DURATION_MS = 8_000
        const val CONTINUATION_TICK_MILLIS = 250L
        const val DIRECT_CHAT_TRANSITION_TIMEOUT_MILLIS = 3_250L
        const val CALL_STARTED_TRANSITION_TIMEOUT_MILLIS = 3_250L
        const val SEMANTIC_PROFILE_TIMEOUT_MILLIS = 25_250L
        const val SEMANTIC_CHOICE_TIMEOUT_MILLIS = 3_250L
        const val WECHAT_VERSION_UNVERIFIED = "UNVERIFIED"
        private const val TASK_SUBMISSION_TAG = "AiFriendTaskSubmission"
        private const val TASK_CONFIRMATION_TAG = "AiFriendTaskConfirm"
        val TERMINAL_STATES = setOf(
            TaskState.CANCELLED,
            TaskState.REJECTED,
            TaskState.SIMULATED,
            TaskState.COMPLETED,
            TaskState.PARTIAL,
            TaskState.FAILED,
        )
        val RESUMABLE_STAGES = setOf(
            TaskStage.COMPLETED,
            TaskStage.CANCELLED,
            TaskStage.FAILED,
        )
    }

    private data class TaskFeedbackKey(
        val stage: TaskStage,
        val taskState: TaskState?,
    )
}

/** 确认识别结果只能进入四个固定分支；UNKNOWN 绝不作为任务纠错上传。 */
internal enum class VoiceConfirmationRoute {
    SUBMIT_EXPECTED,
    SUBMIT_REJECT,
    RETRY_LISTENING,
    FAIL_UNAVAILABLE,
    REPEAT_FULL_TASK,
}

internal fun VoiceConfirmationDecision.confirmationRoute(): VoiceConfirmationRoute = when (this) {
    VoiceConfirmationDecision.CONFIRM -> VoiceConfirmationRoute.SUBMIT_EXPECTED
    VoiceConfirmationDecision.REJECT -> VoiceConfirmationRoute.SUBMIT_REJECT
    VoiceConfirmationDecision.REPEAT -> VoiceConfirmationRoute.REPEAT_FULL_TASK
    VoiceConfirmationDecision.UNKNOWN -> VoiceConfirmationRoute.RETRY_LISTENING
    VoiceConfirmationDecision.UNAVAILABLE -> VoiceConfirmationRoute.FAIL_UNAVAILABLE
}

internal fun LocalTaskRecognition.isExplicitTaskRevision(): Boolean {
    val normalized = transcript.replace(Regex("\\s+"), "")
    if (normalized in setOf("确认", "否认", "拒绝", "取消", "不确认")) return false
    if (normalized in setOf(
            "发送消息", "把消息发出去",
            "拨打电话", "现在打电话",
            "取消这次", "这次不要了",
            "重新说一遍", "我重新说",
        )
    ) return false
    val markers = listOf(
        "不对", "不是", "说错", "改成", "换成", "联系人", "内容",
        "视频电话", "视频通话", "打视频", "语音电话", "语音通话",
        "打电话", "发消息", "发信息", "告诉", "发给",
    )
    return markers.any(normalized::contains)
}
private fun WechatActionType.isWechatCallAction(): Boolean =
    this == WechatActionType.START_VOICE_CALL || this == WechatActionType.START_VIDEO_CALL

/**
 * 微信在另一 Android 用户（常见于厂商应用分身）中运行时，辅助服务拿不到任何页面节点，
 * 最终表现为执行窗口过期。这里给出可操作提示；仍按失败关闭，不猜测或跨用户执行。
 */
internal fun WechatSemanticCallExecutionStatus.wechatCallFailureMessage(callName: String): String =
    when (this) {
        WechatSemanticCallExecutionStatus.WINDOW_EXPIRED,
        WechatSemanticCallExecutionStatus.CALIBRATED_WECHAT_NOT_FOREGROUND,
        -> "未检测到可由小友操作的主微信页面；如果打开的是微信分身，请切换到主微信后重试，${callName}没有发起"
        else -> "没有安全打开${callName}，不会自动重试"
    }
