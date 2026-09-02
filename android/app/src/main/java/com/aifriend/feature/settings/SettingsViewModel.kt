package com.aifriend.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aifriend.core.settings.FontLevel
import com.aifriend.core.settings.SpeechRatePreference
import com.aifriend.core.settings.SpeechVolumePreference
import com.aifriend.core.settings.UserSettings
import com.aifriend.core.settings.UserSettingsRepository
import com.aifriend.core.voice.DialectPackageRegistry
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
import com.aifriend.feature.wechat.supportsMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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

/** 管理当前 owner 的本机适老显示和离线播报设置。 */
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

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch {
            errorMessage.value = null
            runCatching { block() }
                .onFailure { errorMessage.value = "设置保存失败，请重试。" }
        }
    }
}
