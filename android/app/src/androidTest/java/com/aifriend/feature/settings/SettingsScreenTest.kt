package com.aifriend.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aifriend.core.design.AiFriendTheme
import com.aifriend.core.settings.FontLevel
import com.aifriend.core.settings.SpeechRatePreference
import com.aifriend.core.settings.SpeechVolumePreference
import com.aifriend.feature.wechat.WechatSampleCaptureUiState
import com.aifriend.feature.wechat.WechatSampleCaptureDeviceInfo
import com.aifriend.feature.wechat.WechatSampleCaptureResult
import com.aifriend.feature.wechat.WechatSampleCaptureTarget
import com.aifriend.feature.wechat.WechatCalibrationOrientation
import com.aifriend.feature.wechat.WechatCalibrationCaptureUiState
import com.aifriend.feature.wechat.WechatCalibrationProfile
import com.aifriend.feature.wechat.WechatCalibrationProfileKey
import com.aifriend.feature.wechat.WechatCalibrationTarget
import com.aifriend.feature.wechat.WechatNormalizedCalibrationPoint
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 设置页可访问交互、失败关闭方言提示和隐私入口测试。 */
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun calibrationProfilesShowExactDisplayFactsAndRemoveTheSelectedProfile() {
        val key = WechatCalibrationProfileKey(
            manufacturer = "vivo",
            model = "V2536A",
            androidSdkInt = 36,
            displayWidthPixels = 1260,
            displayHeightPixels = 2800,
            densityDpi = 480,
            fontScalePermille = 1_150,
            orientation = WechatCalibrationOrientation.PORTRAIT,
            wechatVersion = "8.0.76",
        )
        val profile = WechatCalibrationProfile(
            key = key,
            points = WechatCalibrationTarget.entries.associateWith {
                WechatNormalizedCalibrationPoint(500_000, 500_000)
            },
            updatedAtEpochMillis = 1L,
        )
        var removed: WechatCalibrationProfileKey? = null
        var startCount = 0
        setContent(
            wechatCalibrationProfiles = WechatCalibrationProfilesUiState(
                currentFingerprint = key,
                profiles = listOf(profile),
                currentProfileAvailable = true,
                message = "当前组合已有精确校准档案。",
            ),
            onRemoveWechatCalibrationProfile = { removed = it },
            onStartWechatCalibration = { startCount++ },
        )

        composeRule.onNodeWithText("开始校准当前组合").performScrollTo().performClick()
        composeRule.onNodeWithText("vivo V2536A").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("1260×2800 / 480 dpi / 字体 115%")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("当前组合").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("移除此档案").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(1, startCount)
            assertEquals(key, removed)
        }
    }

    @Test
    fun choicesAndHighContrastExposeBoundedCallbacks() {
        var speechRate: SpeechRatePreference? = null
        var speechVolume: SpeechVolumePreference? = null
        var fontLevel: FontLevel? = null
        var highContrast: Boolean? = null
        setContent(
            onSpeechRateChanged = { speechRate = it },
            onSpeechVolumeChanged = { speechVolume = it },
            onFontLevelChanged = { fontLevel = it },
            onHighContrastChanged = { highContrast = it },
        )

        composeRule.onNodeWithContentDescription("响亮").performClick()
        composeRule.onNodeWithContentDescription("快速").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("更大字").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("高对比度开关").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(SpeechRatePreference.FAST, speechRate)
            assertEquals(SpeechVolumePreference.LOUD, speechVolume)
            assertEquals(FontLevel.LARGER, fontLevel)
            assertEquals(true, highContrast)
        }
    }

    @Test
    fun capabilityFactsUseExplicitNonColorSemanticsAndOpenSettings() {
        var appSettings = 0
        var accessibilitySettings = 0
        var batterySettings = 0
        setContent(
            state = SettingsUiState(
                dialectPackageAvailable = false,
                capabilities = SettingsCapabilityStatus(
                    network = CapabilityReadState.AVAILABLE,
                    microphone = CapabilityReadState.AVAILABLE,
                    notifications = CapabilityReadState.UNAVAILABLE,
                    restrictedWechatAccessibility = CapabilityReadState.UNKNOWN,
                    accountSession = CapabilityReadState.AVAILABLE,
                    batteryOptimizationExemption = CapabilityReadState.UNAVAILABLE,
                ),
            ),
            onOpenAppPermissionSettings = { appSettings++ },
            onOpenAccessibilitySettings = { accessibilitySettings++ },
            onOpenBatterySettings = { batterySettings++ },
        )

        composeRule.onNodeWithContentDescription("网络，网络可用")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("麦克风，已允许")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("通知，未允许")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("受限微信辅助服务，暂时无法读取")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("管理麦克风与通知").performScrollTo().performClick()
        composeRule.onNodeWithText("打开无障碍设置").performScrollTo().performClick()
        composeRule.onNodeWithText("查看省电设置").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(1, appSettings)
            assertEquals(1, accessibilitySettings)
            assertEquals(1, batterySettings)
        }
    }

    @Test
    fun unsignedDialectPackageIsClearlyUnavailable() {
        setContent()

        composeRule.onNodeWithText("武冈话").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "正式签名武冈话包尚未安装，方言语音联系保持关闭。",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun privacyActionsRemainIndependentExplicitLinks() {
        var help = 0
        var historyDeletion = 0
        var accountClosure = 0
        var routineDeletion = 0
        setContent(
            onOpenHelp = { help++ },
            onOpenTaskHistoryDeletion = { historyDeletion++ },
            onOpenAccountClosure = { accountClosure++ },
            onRequestRoutineCommandDeletion = { routineDeletion++ },
        )

        composeRule.onNodeWithText("打开使用帮助").performScrollTo().performClick()
        composeRule.onNodeWithText("清除日常指令模板").performScrollTo().performClick()
        composeRule.onNodeWithText("清除任务录音与历史").performScrollTo().performClick()
        composeRule.onNodeWithText("永久注销账号").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(1, help)
            assertEquals(1, historyDeletion)
            assertEquals(1, accountClosure)
            assertEquals(1, routineDeletion)
        }
    }

    @Test
    fun routineDeletionConfirmationExplainsScopeAndRequiresExplicitChoice() {
        var confirmed = 0
        var cancelled = 0
        setContent(
            state = SettingsUiState(
                routineCommandDeletion = RoutineCommandDeletionUiState.Confirming,
            ),
            onConfirmRoutineCommandDeletion = { confirmed++ },
            onCancelRoutineCommandDeletion = { cancelled++ },
        )

        composeRule.onNodeWithText(
            "这会清除系统学习到的日常说法，且不能恢复。不会删除联系人称呼，也不会删除确认、取消等安全指令。",
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("再次确认清除").performScrollTo().performClick()
        composeRule.onNodeWithText("取消").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(1, confirmed)
            assertEquals(1, cancelled)
        }
    }

    @Test
    fun debugWechatSamplerExposesEveryBoundedPageTarget() {
        var selectedTarget: WechatSampleCaptureTarget? = null
        setContent(
            wechatSampleCaptureState = WechatSampleCaptureUiState(
                visible = true,
                message = "请选择需要采集的微信页面。",
            ),
            onStartWechatSampleCapture = { selectedTarget = it },
        )

        composeRule.onNodeWithText("采集语音通话选择页")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("采集语音通话中页")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("采集视频通话选择页")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("采集视频通话中页")
            .performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(WechatSampleCaptureTarget.VIDEO_CALL_ACTIVE, selectedTarget)
        }
    }

    @Test
    fun completedWechatSamplerCopiesOneDynamicRuleInputBlock() {
        var copied: String? = null
        val completedTargets = WechatSampleCaptureTarget.entries.mapIndexed { index, target ->
            target to WechatSampleCaptureResult(
                target = target,
                wechatVersion = "8.0.76",
                pageSignatureSha256 = ('a' + index).toString().repeat(64),
                nodeCount = index + 2,
                locatorSourceSha256 = "f".repeat(64)
                    .takeIf { target.requiresLocatorSource },
            )
        }.toMap()
        setContent(
            wechatSampleCaptureState = WechatSampleCaptureUiState(
                visible = true,
                deviceInfo = WechatSampleCaptureDeviceInfo(
                    manufacturer = "Example Manufacturer",
                    model = "Pixel 8 Pro",
                    androidSdk = 35,
                ),
                selectedTarget = WechatSampleCaptureTarget.VIDEO_CALL_ACTIVE,
                completedTargets = completedTargets,
                message = "五类页面均已完成。",
            ),
            onCopyWechatSampleResults = { copied = it },
        )

        composeRule.onNodeWithText("Example Manufacturer / Pixel 8 Pro / Android API 35")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("复制五类规则输入")
            .performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(true, copied?.contains("deviceModel=Pixel\\ 8\\ Pro"))
            assertEquals(true, copied?.contains("videoCallActiveSha256=" + "e".repeat(64)))
        }
    }

    private fun setContent(
        state: SettingsUiState = SettingsUiState(dialectPackageAvailable = false),
        wechatSampleCaptureState: WechatSampleCaptureUiState =
            WechatSampleCaptureUiState(visible = false),
        wechatCalibrationProfiles: WechatCalibrationProfilesUiState =
            WechatCalibrationProfilesUiState(),
        wechatCalibrationCapture: WechatCalibrationCaptureUiState =
            WechatCalibrationCaptureUiState(),
        onSpeechRateChanged: (SpeechRatePreference) -> Unit = {},
        onSpeechVolumeChanged: (SpeechVolumePreference) -> Unit = {},
        onFontLevelChanged: (FontLevel) -> Unit = {},
        onHighContrastChanged: (Boolean) -> Unit = {},
        onOpenHelp: () -> Unit = {},
        onOpenTaskHistoryDeletion: () -> Unit = {},
        onOpenAccountClosure: () -> Unit = {},
        onRequestRoutineCommandDeletion: () -> Unit = {},
        onCancelRoutineCommandDeletion: () -> Unit = {},
        onConfirmRoutineCommandDeletion: () -> Unit = {},
        onDismissRoutineCommandDeletionResult: () -> Unit = {},
        onRefreshRoutineCommands: () -> Unit = {},
        onOpenAppPermissionSettings: () -> Unit = {},
        onOpenAccessibilitySettings: () -> Unit = {},
        onOpenBatterySettings: () -> Unit = {},
        onStartWechatSampleCapture: (WechatSampleCaptureTarget) -> Unit = {},
        onCopyWechatSampleResults: (String) -> Unit = {},
        onRemoveWechatCalibrationProfile:
            (com.aifriend.feature.wechat.WechatCalibrationProfileKey) -> Unit = {},
        onStartWechatCalibration: () -> Unit = {},
        onStartWechatMessageCalibration: () -> Unit = {},
        onCancelWechatCalibration: () -> Unit = {},
    ) {
        composeRule.setContent {
            AiFriendTheme {
                SettingsScreen(
                    state = state,
                    wechatSampleCaptureState = wechatSampleCaptureState,
                    wechatCalibrationProfiles = wechatCalibrationProfiles,
                    wechatCalibrationCapture = wechatCalibrationCapture,
                    onBack = {},
                    onSpeechRateChanged = onSpeechRateChanged,
                    onSpeechVolumeChanged = onSpeechVolumeChanged,
                    onFontLevelChanged = onFontLevelChanged,
                    onHighContrastChanged = onHighContrastChanged,
                    onOpenHelp = onOpenHelp,
                    onOpenTaskHistoryDeletion = onOpenTaskHistoryDeletion,
                    onOpenAccountClosure = onOpenAccountClosure,
                    onRequestRoutineCommandDeletion = onRequestRoutineCommandDeletion,
                    onCancelRoutineCommandDeletion = onCancelRoutineCommandDeletion,
                    onConfirmRoutineCommandDeletion = onConfirmRoutineCommandDeletion,
                    onDismissRoutineCommandDeletionResult =
                        onDismissRoutineCommandDeletionResult,
                    onRefreshRoutineCommands = onRefreshRoutineCommands,
                    onOpenAppPermissionSettings = onOpenAppPermissionSettings,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenBatterySettings = onOpenBatterySettings,
                    onStartWechatSampleCapture = onStartWechatSampleCapture,
                    onCopyWechatSampleResults = onCopyWechatSampleResults,
                    onRemoveWechatCalibrationProfile = onRemoveWechatCalibrationProfile,
                    onStartWechatCalibration = onStartWechatCalibration,
                    onStartWechatMessageCalibration = onStartWechatMessageCalibration,
                    onCancelWechatCalibration = onCancelWechatCalibration,
                    onDismissError = {},
                )
            }
        }
    }
}
