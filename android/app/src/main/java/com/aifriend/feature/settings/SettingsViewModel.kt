package com.aifriend.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aifriend.core.settings.FontLevel
import com.aifriend.core.settings.SpeechRatePreference
import com.aifriend.core.settings.SpeechVolumePreference
import com.aifriend.core.settings.UserSettings
import com.aifriend.core.settings.UserSettingsRepository
import com.aifriend.core.voice.DialectPackageRegistry
import com.aifriend.contract.model.Consent
import com.aifriend.contract.model.ConsentDecision
import com.aifriend.contract.model.ConsentType
import com.aifriend.feature.consent.ConsentRepository
import com.aifriend.feature.personalization.AmbiguousCallChoice
import com.aifriend.feature.personalization.DialogueStyleChoice
import com.aifriend.feature.personalization.DisabledPersonalMemoryRepository
import com.aifriend.feature.personalization.PersonalMemoryChoices
import com.aifriend.feature.personalization.PersonalMemoryRepository
import com.aifriend.feature.wechat.WechatSampleCaptureCoordinator
import com.aifriend.feature.wechat.WechatSampleCaptureTarget
import com.aifriend.feature.wechat.WechatSampleCaptureUiState
import com.aifriend.feature.wechat.WechatCalibrationFingerprintProvider
import com.aifriend.feature.wechat.WechatCalibrationCaptureCoordinator
import com.aifriend.feature.wechat.WechatCalibrationCaptureUiState
import com.aifriend.feature.wechat.WechatCalibrationProfile
import com.aifriend.feature.wechat.WechatCalibrationProfileKey
import com.aifriend.feature.wechat.WechatCalibrationProfileRegistry
import com.aifriend.feature.wechat.WechatCalibrationPurpose
import com.aifriend.feature.wechat.WechatRuntimeVersionProvider
import com.aifriend.feature.wechat.WechatMessageCalibrationShareLauncher
import com.aifriend.feature.wechat.supportsCall
import com.aifriend.feature.wechat.supportsLegacyCallWithoutChatAvatar
import com.aifriend.feature.wechat.supportsMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 设置页状态；只有验签包才会显示为正式方言能力。 */
data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val dialectPackageAvailable: Boolean = false,
    val capabilities: SettingsCapabilityStatus? = null,
    val routineCommandDeletion: RoutineCommandDeletionUiState =
        RoutineCommandDeletionUiState.Idle,
    val routineCommands: RoutineCommandListUiState =
        RoutineCommandListUiState.Loading,
    val errorMessage: String? = null,
)

/** 当前安装内的微信通话校准档案；档案按设备与显示指纹精确匹配。 */
data class WechatCalibrationProfilesUiState(
    val currentFingerprint: WechatCalibrationProfileKey? = null,
    val profiles: List<WechatCalibrationProfile> = emptyList(),
    val currentProfileAvailable: Boolean = false,
    val message: String = "正在读取微信通话校准档案……",
)

/** 日常指令模板清除必须经历明确的第二次确认。 */
sealed interface RoutineCommandDeletionUiState {
    data object Idle : RoutineCommandDeletionUiState
    data object Confirming : RoutineCommandDeletionUiState
    data object Submitting : RoutineCommandDeletionUiState
    data class Completed(val deletedCount: Int) : RoutineCommandDeletionUiState
    data class Failed(val message: String) : RoutineCommandDeletionUiState
}

/** 日常指令只展示有限动作类别计数，不展示原始说法。 */
sealed interface RoutineCommandListUiState {
    data object Loading : RoutineCommandListUiState
    data class Ready(val overview: RoutineCommandTemplateOverview) : RoutineCommandListUiState
    data object Failed : RoutineCommandListUiState
}

/** 长期个人偏好页面只保留有限枚举与管理状态。 */
enum class PersonalMemoryOperation {
    LOADING,
    READY,
    SAVING_CONSENT,
    SAVING,
    DELETING,
    REVOKING,
    FAILED,
}

data class PersonalMemoryUiState(
    val operation: PersonalMemoryOperation = PersonalMemoryOperation.LOADING,
    val featureEnabled: Boolean = false,
    val consentGranted: Boolean = false,
    val policyVersion: String = "",
    val choices: PersonalMemoryChoices = PersonalMemoryChoices.SafeDefault,
    val savedChoicesPresent: Boolean = false,
    val version: Long? = null,
    val deletionConfirmationRequested: Boolean = false,
    val revocationConfirmationRequested: Boolean = false,
    val message: String? = null,
)

/** 管理当前 owner 的本机适老显示、离线播报和有限长期偏好。 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: UserSettingsRepository,
    dialectPackageRegistry: DialectPackageRegistry,
    private val capabilityReader: SettingsCapabilityReader,
    private val routineCommandDeletionRepository: RoutineCommandDeletionRepository,
    private val wechatSampleCaptureCoordinator: WechatSampleCaptureCoordinator,
    private val wechatRuntimeVersionProvider: WechatRuntimeVersionProvider,
    private val wechatCalibrationFingerprintProvider: WechatCalibrationFingerprintProvider,
    private val wechatCalibrationProfileRegistry: WechatCalibrationProfileRegistry,
    private val wechatCalibrationCaptureCoordinator: WechatCalibrationCaptureCoordinator,
    private val wechatMessageCalibrationShareLauncher: WechatMessageCalibrationShareLauncher =
        WechatMessageCalibrationShareLauncher { false },
    private val personalMemoryRepository: PersonalMemoryRepository =
        DisabledPersonalMemoryRepository,
    private val consentRepository: ConsentRepository = DisabledSettingsConsentRepository,
) : ViewModel() {
    private val errorMessage = MutableStateFlow<String?>(null)
    private val capabilities = MutableStateFlow<SettingsCapabilityStatus?>(null)
    private val routineCommandDeletion =
        MutableStateFlow<RoutineCommandDeletionUiState>(RoutineCommandDeletionUiState.Idle)
    private val routineCommands =
        MutableStateFlow<RoutineCommandListUiState>(RoutineCommandListUiState.Loading)
    private val dialectPackageAvailable = dialectPackageRegistry.formalPackageAvailable()
    private val mutableWechatCalibrationProfiles =
        MutableStateFlow(WechatCalibrationProfilesUiState())
    private val mutablePersonalMemory = MutableStateFlow(PersonalMemoryUiState())
    private var personalMemoryRefreshJob: kotlinx.coroutines.Job? = null

    val personalMemory: StateFlow<PersonalMemoryUiState> = mutablePersonalMemory.asStateFlow()

    val wechatSampleCaptureState: StateFlow<WechatSampleCaptureUiState> =
        wechatSampleCaptureCoordinator.state

    val capabilityStatus: StateFlow<SettingsCapabilityStatus?> = capabilities.asStateFlow()

    val wechatCalibrationProfiles: StateFlow<WechatCalibrationProfilesUiState> =
        mutableWechatCalibrationProfiles.asStateFlow()

    val wechatCalibrationCapture: StateFlow<WechatCalibrationCaptureUiState> =
        wechatCalibrationCaptureCoordinator.state

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        capabilities,
        routineCommandDeletion,
        routineCommands,
        errorMessage,
    ) { settings, capabilityStatus, deletion, commands, error ->
        SettingsUiState(
            settings = settings,
            dialectPackageAvailable = dialectPackageAvailable,
            capabilities = capabilityStatus,
            routineCommandDeletion = deletion,
            routineCommands = commands,
            errorMessage = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState(dialectPackageAvailable = dialectPackageAvailable),
    )

    init {
        refreshCapabilityStatus()
        refreshRoutineCommands()
        refreshWechatCalibrationProfiles()
        refreshPersonalMemory()
    }

    fun setSpeechRate(value: SpeechRatePreference) = update {
        settingsRepository.updateSpeechRate(value)
    }

    fun setSpeechVolume(value: SpeechVolumePreference) = update {
        settingsRepository.updateSpeechVolume(value)
    }

    fun setFontLevel(value: FontLevel) = update {
        settingsRepository.updateFontLevel(value)
    }

    fun setHighContrast(enabled: Boolean) = update {
        settingsRepository.updateHighContrast(enabled)
    }

    fun dismissError() {
        errorMessage.value = null
    }

    fun requestRoutineCommandDeletion() {
        if (routineCommandDeletion.value != RoutineCommandDeletionUiState.Submitting) {
            routineCommandDeletion.value = RoutineCommandDeletionUiState.Confirming
        }
    }

    fun cancelRoutineCommandDeletion() {
        if (routineCommandDeletion.value == RoutineCommandDeletionUiState.Confirming) {
            routineCommandDeletion.value = RoutineCommandDeletionUiState.Idle
        }
    }

    fun confirmRoutineCommandDeletion() {
        if (routineCommandDeletion.value != RoutineCommandDeletionUiState.Confirming) return
        routineCommandDeletion.value = RoutineCommandDeletionUiState.Submitting
        viewModelScope.launch {
            runCatching { routineCommandDeletionRepository.clear() }
                .onSuccess { deletedCount ->
                    routineCommandDeletion.value =
                        RoutineCommandDeletionUiState.Completed(deletedCount)
                    routineCommands.value = RoutineCommandListUiState.Ready(
                        RoutineCommandTemplateOverview.Empty,
                    )
                }
                .onFailure {
                    routineCommandDeletion.value = RoutineCommandDeletionUiState.Failed(
                        "清除未完成，请检查网络后重新确认。",
                    )
                }
        }
    }

    fun dismissRoutineCommandDeletionResult() {
        if (routineCommandDeletion.value != RoutineCommandDeletionUiState.Submitting) {
            routineCommandDeletion.value = RoutineCommandDeletionUiState.Idle
        }
    }

    fun refreshCapabilityStatus() {
        runCatching(capabilityReader::read)
            .onSuccess { capabilities.value = it }
            .onFailure { errorMessage.value = "设备状态读取失败，请重新进入本页。" }
    }

    fun refreshRoutineCommands() {
        if (routineCommandDeletion.value == RoutineCommandDeletionUiState.Submitting) return
        routineCommands.value = RoutineCommandListUiState.Loading
        viewModelScope.launch {
            runCatching { routineCommandDeletionRepository.list() }
                .onSuccess { overview ->
                    routineCommands.value = RoutineCommandListUiState.Ready(overview)
                }
                .onFailure { routineCommands.value = RoutineCommandListUiState.Failed }
        }
    }

    fun refreshPersonalMemory() {
        val current = mutablePersonalMemory.value
        if (
            personalMemoryRefreshJob?.isActive == true ||
            current.operation in PERSONAL_MEMORY_MUTATION_OPERATIONS
        ) return
        mutablePersonalMemory.value = current.copy(
            operation = PersonalMemoryOperation.LOADING,
            message = null,
        )
        personalMemoryRefreshJob = viewModelScope.launch {
            runCatching { personalMemoryRepository.read() }
                .onSuccess { snapshot ->
                    val choices = snapshot.choices ?: PersonalMemoryChoices(
                        speechRate = uiState.value.settings.speechRate,
                    )
                    mutablePersonalMemory.value = PersonalMemoryUiState(
                        operation = PersonalMemoryOperation.READY,
                        featureEnabled = snapshot.featureEnabled,
                        consentGranted = snapshot.consentGranted,
                        policyVersion = snapshot.policyVersion,
                        choices = choices,
                        savedChoicesPresent = snapshot.choices != null,
                        version = snapshot.version,
                    )
                    snapshot.activeChoicesOrNull()?.let { active ->
                        syncLocalSpeechRate(active.speechRate)
                    }
                }
                .onFailure { exception ->
                    if (exception is CancellationException) throw exception
                    personalMemoryRepository.invalidate()
                    mutablePersonalMemory.value = current.copy(
                        operation = PersonalMemoryOperation.FAILED,
                        message = exception.message
                            ?: "长期偏好读取失败，已使用安全默认值。",
                    )
                }
        }
    }

    fun setPersonalMemorySpeechRate(value: SpeechRatePreference) = updatePersonalDraft {
        copy(speechRate = value)
    }

    fun setPersonalMemoryDialogueStyle(value: DialogueStyleChoice) = updatePersonalDraft {
        copy(dialogueStyle = value)
    }

    fun setPersonalMemoryAmbiguousCall(value: AmbiguousCallChoice) = updatePersonalDraft {
        copy(ambiguousCall = value)
    }

    /** 只在用户看清用途并明确点击后单独授权。 */
    fun grantPersonalMemoryConsent() {
        val current = mutablePersonalMemory.value
        if (current.operation != PersonalMemoryOperation.READY ||
            current.consentGranted || !current.featureEnabled ||
            current.policyVersion.isBlank()
        ) return
        mutablePersonalMemory.value = current.copy(
            operation = PersonalMemoryOperation.SAVING_CONSENT,
            message = null,
        )
        viewModelScope.launch {
            runCatching {
                consentRepository.update(
                    ConsentType.PERSONAL_MEMORY,
                    ConsentDecision.GRANTED,
                    current.policyVersion,
                )
            }.onSuccess {
                mutablePersonalMemory.value = current.copy(
                    operation = PersonalMemoryOperation.READY,
                    consentGranted = true,
                    message = "已同意长期偏好用途，请确认三项选择后保存。",
                )
            }.onFailure { exception ->
                if (exception is CancellationException) throw exception
                personalMemoryRepository.invalidate()
                mutablePersonalMemory.value = current.copy(
                    operation = PersonalMemoryOperation.FAILED,
                    message = exception.message ?: "长期偏好授权没有保存，请重试。",
                )
            }
        }
    }

    fun savePersonalMemory() {
        val current = mutablePersonalMemory.value
        if (current.operation != PersonalMemoryOperation.READY ||
            !current.featureEnabled || !current.consentGranted
        ) return
        mutablePersonalMemory.value = current.copy(
            operation = PersonalMemoryOperation.SAVING,
            message = null,
        )
        viewModelScope.launch {
            runCatching {
                personalMemoryRepository.update(
                    current.choices,
                    current.version ?: 0L,
                )
            }.onSuccess { snapshot ->
                val saved = checkNotNull(snapshot.choices)
                syncLocalSpeechRate(saved.speechRate)
                mutablePersonalMemory.value = PersonalMemoryUiState(
                    operation = PersonalMemoryOperation.READY,
                    featureEnabled = snapshot.featureEnabled,
                    consentGranted = snapshot.consentGranted,
                    policyVersion = snapshot.policyVersion,
                    choices = saved,
                    savedChoicesPresent = true,
                    version = snapshot.version,
                    message = "长期偏好已保存；联系任务仍会完整复述并等待您确认。",
                )
            }.onFailure { exception ->
                if (exception is CancellationException) throw exception
                personalMemoryRepository.invalidate()
                mutablePersonalMemory.value = current.copy(
                    operation = PersonalMemoryOperation.FAILED,
                    message = exception.message ?: "长期偏好没有保存，请刷新后重试。",
                )
            }
        }
    }

    fun requestPersonalMemoryDeletion() {
        val current = mutablePersonalMemory.value
        if (current.operation == PersonalMemoryOperation.READY &&
            current.savedChoicesPresent && current.version != null
        ) {
            mutablePersonalMemory.value = current.copy(
                deletionConfirmationRequested = true,
                revocationConfirmationRequested = false,
                message = null,
            )
        }
    }

    fun cancelPersonalMemoryDeletion() {
        mutablePersonalMemory.value = mutablePersonalMemory.value.copy(
            deletionConfirmationRequested = false,
        )
    }

    fun confirmPersonalMemoryDeletion() {
        val current = mutablePersonalMemory.value
        val version = current.version ?: return
        if (current.operation != PersonalMemoryOperation.READY ||
            !current.deletionConfirmationRequested
        ) return
        personalMemoryRepository.invalidate()
        mutablePersonalMemory.value = current.copy(
            operation = PersonalMemoryOperation.DELETING,
            deletionConfirmationRequested = false,
            message = null,
        )
        viewModelScope.launch {
            runCatching { personalMemoryRepository.delete(version) }
                .onSuccess { snapshot ->
                    mutablePersonalMemory.value = PersonalMemoryUiState(
                        operation = PersonalMemoryOperation.READY,
                        featureEnabled = snapshot.featureEnabled,
                        consentGranted = snapshot.consentGranted,
                        policyVersion = snapshot.policyVersion,
                        choices = PersonalMemoryChoices(
                            speechRate = uiState.value.settings.speechRate,
                        ),
                        savedChoicesPresent = false,
                        version = snapshot.version,
                        message = "长期偏好内容已删除，任务已回到安全默认方式。",
                    )
                }
                .onFailure { exception ->
                    if (exception is CancellationException) throw exception
                    mutablePersonalMemory.value = current.copy(
                        operation = PersonalMemoryOperation.FAILED,
                        deletionConfirmationRequested = false,
                        message = exception.message
                            ?: "长期偏好删除结果不确定，已停止使用并请刷新。",
                    )
                }
        }
    }

    fun requestPersonalMemoryRevocation() {
        val current = mutablePersonalMemory.value
        if (current.operation == PersonalMemoryOperation.READY && current.consentGranted) {
            mutablePersonalMemory.value = current.copy(
                revocationConfirmationRequested = true,
                deletionConfirmationRequested = false,
                message = null,
            )
        }
    }

    fun cancelPersonalMemoryRevocation() {
        mutablePersonalMemory.value = mutablePersonalMemory.value.copy(
            revocationConfirmationRequested = false,
        )
    }

    fun confirmPersonalMemoryRevocation() {
        val current = mutablePersonalMemory.value
        if (current.operation != PersonalMemoryOperation.READY ||
            !current.revocationConfirmationRequested || current.policyVersion.isBlank()
        ) return
        personalMemoryRepository.invalidate()
        mutablePersonalMemory.value = current.copy(
            operation = PersonalMemoryOperation.REVOKING,
            revocationConfirmationRequested = false,
            message = null,
        )
        viewModelScope.launch {
            runCatching {
                consentRepository.update(
                    ConsentType.PERSONAL_MEMORY,
                    ConsentDecision.REVOKED,
                    current.policyVersion,
                )
            }.onSuccess {
                mutablePersonalMemory.value = current.copy(
                    operation = PersonalMemoryOperation.READY,
                    consentGranted = false,
                    savedChoicesPresent = false,
                    choices = PersonalMemoryChoices(
                        speechRate = uiState.value.settings.speechRate,
                    ),
                    message = "已撤回授权并要求服务端立即清除长期偏好内容。",
                )
            }.onFailure { exception ->
                if (exception is CancellationException) throw exception
                mutablePersonalMemory.value = current.copy(
                    operation = PersonalMemoryOperation.FAILED,
                    message = exception.message
                        ?: "授权撤回没有完成，已停止使用长期偏好。",
                )
            }
        }
    }

    private suspend fun syncLocalSpeechRate(value: SpeechRatePreference) {
        try {
            settingsRepository.updateSpeechRate(value)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // 云端偏好已保存；本机播报设置同步失败时不扩大为任务授权。
        }
    }
    private fun updatePersonalDraft(
        change: PersonalMemoryChoices.() -> PersonalMemoryChoices,
    ) {
        val current = mutablePersonalMemory.value
        if (current.operation != PersonalMemoryOperation.READY || !current.featureEnabled) return
        mutablePersonalMemory.value = current.copy(
            choices = current.choices.change(),
            message = null,
        )
    }
    fun reportSystemSettingsUnavailable() {
        errorMessage.value = "无法打开系统设置，请从手机设置中手动查找本应用。"
    }

    fun beginWechatSampleCapture(target: WechatSampleCaptureTarget): Boolean =
        wechatSampleCaptureCoordinator.begin(target)

    fun refreshWechatSampleCapture() {
        wechatSampleCaptureCoordinator.refresh()
    }

    fun reportWechatLaunchFailure() {
        wechatSampleCaptureCoordinator.failToOpenWechat()
    }

    fun refreshWechatCalibrationProfiles() {
        mutableWechatCalibrationProfiles.value = runCatching {
            val profiles = wechatCalibrationProfileRegistry.list()
            val wechatVersion = wechatRuntimeVersionProvider.readCurrentVersion()
            val fingerprint = wechatVersion?.let(wechatCalibrationFingerprintProvider::current)
            WechatCalibrationProfilesUiState(
                currentFingerprint = fingerprint,
                profiles = profiles,
                currentProfileAvailable = fingerprint != null &&
                    profiles.any { profile -> profile.key == fingerprint && profile.supportsCall },
                message = when {
                    wechatVersion == null ->
                        "未检测到微信版本；已有档案仍会保留。"
                    fingerprint == null ->
                        "无法读取当前显示参数；微信自动通话保持关闭。"
                    profiles.any { profile -> profile.key == fingerprint } -> {
                        val profile = profiles.first { it.key == fingerprint }
                        when {
                            profile.supportsCall && profile.supportsMessage ->
                                "当前组合的通话和消息发送均已校准。"
                            profile.supportsCall ->
                                "当前组合已校准通话；消息发送还需单独校准。"
                            profile.supportsLegacyCallWithoutChatAvatar ->
                                "通话导航已更新；请补录聊天页右上角更多选项和聊天信息页亲友头像，已有其他点位会保留。"
                            else ->
                                "当前组合已校准消息发送；通话还需单独校准。"
                        }
                    }
                    else ->
                        "当前组合尚未校准；其他功能不受影响，微信自动通话保持关闭。"
                },
            )
        }.getOrElse {
            WechatCalibrationProfilesUiState(
                message = "校准档案读取失败；微信自动通话保持关闭。",
            )
        }
    }

    fun removeWechatCalibrationProfile(key: WechatCalibrationProfileKey) {
        if (!runCatching { wechatCalibrationProfileRegistry.remove(key) }.getOrDefault(false)) {
            mutableWechatCalibrationProfiles.value =
                mutableWechatCalibrationProfiles.value.copy(
                    message = "校准档案移除失败，请稍后重试。",
                )
            return
        }
        refreshWechatCalibrationProfiles()
    }

    fun beginWechatCalibration(): Boolean = wechatCalibrationCaptureCoordinator.start()

    fun beginWechatMessageCalibration(): Boolean {
        if (!wechatCalibrationCaptureCoordinator.start(WechatCalibrationPurpose.MESSAGE)) {
            return false
        }
        if (wechatMessageCalibrationShareLauncher.launch()) return true
        wechatCalibrationCaptureCoordinator.failToOpenWechat()
        return false
    }

    fun cancelWechatCalibration() = wechatCalibrationCaptureCoordinator.cancel()

    fun reportWechatCalibrationLaunchFailure() =
        wechatCalibrationCaptureCoordinator.failToOpenWechat()

    private companion object {
        val PERSONAL_MEMORY_MUTATION_OPERATIONS = setOf(
            PersonalMemoryOperation.SAVING_CONSENT,
            PersonalMemoryOperation.SAVING,
            PersonalMemoryOperation.DELETING,
            PersonalMemoryOperation.REVOKING,
        )
    }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch {
            errorMessage.value = null
            runCatching { block() }
                .onFailure { errorMessage.value = "设置保存失败，请重试。" }
        }
    }
}

/** 旧测试构造路径不会隐式授权。 */
private object DisabledSettingsConsentRepository : ConsentRepository {
    override suspend fun listCurrent(): List<Consent> = emptyList()

    override suspend fun update(
        type: ConsentType,
        decision: ConsentDecision,
        policyVersion: String,
    ): Consent = error("长期偏好授权不可用")
}
