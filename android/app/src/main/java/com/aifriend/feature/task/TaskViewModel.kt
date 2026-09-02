package com.aifriend.feature.task

import android.os.SystemClock
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
import com.aifriend.contract.model.WechatActionType
import com.aifriend.core.audio.AudioCapturePort
import com.aifriend.core.audio.AudioPlaybackContent
import com.aifriend.core.audio.CapturedAudio
import com.aifriend.core.audio.toAudioCaptureFailurePresentation
import com.aifriend.core.audio.toAudioPlaybackUserMessage
import com.aifriend.core.feedback.HapticFeedbackPort
import com.aifriend.core.network.toChineseUserMessage
import com.aifriend.core.voice.DialectPackageRegistry
import com.aifriend.feature.audio.AudioUploadRepository
import com.aifriend.feature.guardian.GuardianTaskInbox
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
    private val safetyCommandMatcher: SafetyCommandMatcher,
    private val dialectPackageRegistry: DialectPackageRegistry,
    private val guardianTaskInbox: GuardianTaskInbox,
    private val executionGate: TaskExecutionGate,
    private val wechatExecutionCoordinator: WechatExecutionCoordinator,
    private val wechatMessageDeliveryCoordinator: WechatMessageDeliveryCoordinator,
    private val wechatMessageHandoffPortFactory: WechatMessageHandoffPortFactory,
    private val rehearsalCoordinator: TaskRehearsalCoordinator,
    private val hapticFeedbackPort: HapticFeedbackPort,
    private val continuationAnnouncer: MessageContinuationAnnouncer,
) : ViewModel() {

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
    private var generation = 0L
    private var executionToken = executionGate.open()
    private var guardianResumeEligible = false
    private var pendingWechatActionPlan: com.aifriend.contract.model.WechatActionPlan? = null
    private var pendingWechatMessageAudio: CapturedAudio? = null
    private var rehearsedSummaryHash: String? = null
    private var pendingPreviousConfirmedContactId: String? = null
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
                val audioObjectId = audioUploadRepository.upload(
                    AudioPurpose.TASK,
                    CreateAudioUploadTicketRequest.MediaType.AUDIO_SLASH_WAV,
                    captured.durationMs,
                    captured.wavBytes,
                )
                ensureCurrent(current, token)
                val currentWechatVersion = wechatExecutionCoordinator.currentWechatVersion()
                val session = taskRepository.create(
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
                ensureCurrent(current, token)
                showSession(session)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
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

    fun startConfirmation(action: ConfirmationAction) {
        val state = mutableUiState.value
        val session = state.session ?: return
        if (!state.canStartConfirmation(action, rehearsedSummaryHash)) return
        mutableUiState.value = mutableUiState.value.copy(pendingConfirmationAction = action)
        startRecording(TaskStage.RECORDING_CONFIRMATION, CONFIRMATION_MAX_DURATION_MS)
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
        val action = state.pendingConfirmationAction ?: return
        if (state.stage != TaskStage.RECORDING_CONFIRMATION) return
        val current = generation
        val token = executionToken
        mutableUiState.value = state.copy(
            stage = TaskStage.MATCHING_CONFIRMATION,
            statusMessage = "正在核对个人安全指令",
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
                val templateId = safetyCommandMatcher.match(captured.wavBytes, action)
                    ?: error("没有唯一识别到个人安全指令，本次任务未确认")
                if (action == ConfirmationAction.CANCEL) {
                    executionGate.block(token)
                    confirmationAudio?.clear()
                    confirmationAudio = null
                    showLocalCancellation(session, "已识别取消指令，本机已立即阻断后续动作")
                    val outcome = try {
                        taskRepository.confirm(
                            session,
                            action,
                            templateId,
                            OffsetDateTime.now(),
                        )
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Exception) {
                        if (executionGate.isCurrent(token)) {
                            showLocalCancellation(
                                session,
                                "本机已取消；服务端状态未确认，仍不会继续执行",
                            )
                        }
                        return@launch
                    }
                    if (executionGate.isCurrent(token)) {
                        showLocalCancellation(outcome.session, outcome.session.state.userMessage())
                    }
                    return@launch
                }
                val outcome = taskRepository.confirm(
                    session,
                    action,
                    templateId,
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
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                failIfCurrent(
                    current,
                    token,
                    exception.toChineseUserMessage("确认没有完成，请重新说"),
                )
            }
        }
    }

    fun leave() {
        generation++
        executionGate.invalidate()
        activeJob?.cancel()
        clearContinuationWindow()
        rehearsalCoordinator.close()
        viewModelScope.launch {
            rehearsalCoordinator.stop()
            runCatching { audioCapturePort.cancel() }
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
        activeJob = viewModelScope.launch {
            rehearsalCoordinator.stop()
            runCatching { audioCapturePort.cancel() }
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

    /** 只有守护发起且已终止的任务，才允许由可见页面明确恢复。 */
    suspend fun leaveForGuardianResume(): Boolean {
        val state = mutableUiState.value
        val eligible = state.guardianResumeAvailable && state.stage in RESUMABLE_STAGES
        if (!eligible) return false
        generation++
        executionGate.invalidate()
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
        return true
    }

    private fun startRecording(stage: TaskStage, maximumMs: Int) {
        val current = generation
        val token = executionToken
        mutableUiState.value = mutableUiState.value.copy(
            stage = stage,
            microphonePermissionRecoveryRequired = false,
            statusMessage = "正在录音，点击停止",
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
    fun acceptVerifiedChannelResult(session: com.aifriend.contract.model.TaskSession) {
        if (mutableUiState.value.session?.sessionId != session.sessionId) return
        clearContinuationWindow()
        showSession(session)
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
        clearWechatActionPlan()
        rehearsedSummaryHash = null
        if (session.state == TaskState.AWAITING_CONFIRMATION) {
            beginRehearsal(session)
            return
        }
        if (session.state != TaskState.AWAITING_SELECTION) clearAudio()
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
                retainedMessageAudio = null
                rehearsedSummaryHash = summaryHash
                mutableUiState.value = TaskUiState(
                    stage = TaskStage.ACTIVE,
                    session = session,
                    guardianResumeAvailable = guardianResumeEligible,
                    statusMessage = "完整复述已播放，请说对应的个人安全指令",
                )
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
    }

    private fun failAndClear(
        message: String,
        microphonePermissionRecoveryRequired: Boolean = false,
    ) {
        executionGate.block(executionToken)
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
        taskAudio?.clear()
        confirmationAudio?.clear()
        taskAudio = null
        confirmationAudio = null
    }

    /** 动作计划只驻留当前 ViewModel；退出、失败、取消或状态切换时立即丢弃。 */
    private fun clearWechatActionPlan() {
        wechatTransitionTimeoutJob?.cancel()
        wechatTransitionTimeoutJob = null
        pendingWechatActionPlan = null
        pendingWechatMessageAudio?.clear()
        pendingWechatMessageAudio = null
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
                    statusMessage = "完整复述已重新播放，请说对应的个人安全指令",
                )
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
                acceptVerifiedChannelResult(terminalSession)
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

        val report = outcome.finalDeliveryReportOrNull() ?: return
        val accepted = outcome.status == WechatSemanticCallExecutionStatus.HANDED_TO_WECHAT
        val callName = if (plan.action == WechatActionType.START_VOICE_CALL) {
            "语音通话"
        } else {
            "视频通话"
        }
        val current = generation
        val token = executionToken
        clearWechatActionPlan()
        mutableUiState.value = mutableUiState.value.copy(
            statusMessage = if (accepted) {
                "已交给微信处理；请在微信中查看，未确认通话已开始"
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
                        "已交给微信处理；请在微信中查看，未确认通话已开始"
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
        const val CONTINUATION_TICK_MILLIS = 250L
        const val DIRECT_CHAT_TRANSITION_TIMEOUT_MILLIS = 3_250L
        const val CALL_STARTED_TRANSITION_TIMEOUT_MILLIS = 3_250L
        const val SEMANTIC_PROFILE_TIMEOUT_MILLIS = 25_250L
        const val SEMANTIC_CHOICE_TIMEOUT_MILLIS = 3_250L
        const val WECHAT_VERSION_UNVERIFIED = "UNVERIFIED"
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

private fun WechatActionType.isWechatCallAction(): Boolean =
    this == WechatActionType.START_VOICE_CALL || this == WechatActionType.START_VIDEO_CALL
