package com.aifriend.feature.settings

import com.aifriend.app.ui.components.toChineseUiMessage

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aifriend.core.settings.FontLevel
import com.aifriend.core.settings.SpeechRatePreference
import com.aifriend.core.settings.SpeechVolumePreference
import com.aifriend.feature.wechat.WechatSampleCaptureUiState
import com.aifriend.feature.wechat.WechatSampleCaptureTarget
import com.aifriend.feature.wechat.WechatCalibrationOrientation
import com.aifriend.feature.wechat.WechatCalibrationCaptureUiState
import com.aifriend.feature.wechat.WechatCalibrationProfileKey
import com.aifriend.feature.wechat.supportsCall
import com.aifriend.feature.wechat.supportsMessage
import com.aifriend.feature.wechat.buildWechatRuleInputPropertiesOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** 将设置 ViewModel 绑定到页面。 */
@Suppress("DEPRECATION")
@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenTaskHistoryDeletion: () -> Unit,
    onOpenAccountClosure: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val wechatSampleCaptureState by viewModel.wechatSampleCaptureState.collectAsState()
    val wechatCalibrationProfiles by viewModel.wechatCalibrationProfiles.collectAsState()
    val wechatCalibrationCapture by viewModel.wechatCalibrationCapture.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshCapabilityStatus()
                viewModel.refreshRoutineCommands()
                viewModel.refreshWechatSampleCapture()
                viewModel.refreshWechatCalibrationProfiles()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val openSystemSettings: (Intent) -> Unit = { intent ->
        runCatching { context.startActivity(intent) }
            .onFailure { viewModel.reportSystemSettingsUnavailable() }
    }
    SettingsScreen(
        state = state,
        wechatSampleCaptureState = wechatSampleCaptureState,
        wechatCalibrationProfiles = wechatCalibrationProfiles,
        wechatCalibrationCapture = wechatCalibrationCapture,
        onBack = onBack,
        onSpeechRateChanged = viewModel::setSpeechRate,
        onSpeechVolumeChanged = viewModel::setSpeechVolume,
        onFontLevelChanged = viewModel::setFontLevel,
        onHighContrastChanged = viewModel::setHighContrast,
        onOpenHelp = onOpenHelp,
        onOpenTaskHistoryDeletion = onOpenTaskHistoryDeletion,
        onOpenAccountClosure = onOpenAccountClosure,
        onRequestRoutineCommandDeletion = viewModel::requestRoutineCommandDeletion,
        onCancelRoutineCommandDeletion = viewModel::cancelRoutineCommandDeletion,
        onConfirmRoutineCommandDeletion = viewModel::confirmRoutineCommandDeletion,
        onDismissRoutineCommandDeletionResult =
            viewModel::dismissRoutineCommandDeletionResult,
        onRefreshRoutineCommands = viewModel::refreshRoutineCommands,
        onOpenAppPermissionSettings = {
            openSystemSettings(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        },
        onOpenAccessibilitySettings = {
            openSystemSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        onOpenBatterySettings = {
            openSystemSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        },
        onStartWechatSampleCapture = { target ->
            if (viewModel.beginWechatSampleCapture(target)) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(
                    "com.tencent.mm",
                )
                if (launchIntent == null) {
                    viewModel.reportWechatLaunchFailure()
                } else {
                    runCatching { context.startActivity(launchIntent) }
                        .onFailure { viewModel.reportWechatLaunchFailure() }
                }
            }
        },
        onCopyWechatSampleResults = { properties ->
            val copied = runCatching {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                    ?: error("clipboard unavailable")
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("AI好友微信规则采样", properties),
                )
            }.isSuccess
            Toast.makeText(
                context,
                if (copied) {
                    "五类采样结果已复制，请粘贴后及时清除系统剪贴板。"
                } else {
                    "无法复制采样结果，请稍后重试。"
                },
                Toast.LENGTH_LONG,
            ).show()
        },
        onRemoveWechatCalibrationProfile = viewModel::removeWechatCalibrationProfile,
        onStartWechatCalibration = {
            if (viewModel.beginWechatCalibration()) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(
                    "com.tencent.mm",
                )
                if (launchIntent == null) {
                    viewModel.reportWechatCalibrationLaunchFailure()
                } else {
                    runCatching { context.startActivity(launchIntent) }
                        .onFailure { viewModel.reportWechatCalibrationLaunchFailure() }
                }
            }
        },
        onStartWechatMessageCalibration = {
            viewModel.beginWechatMessageCalibration()
        },
        onCancelWechatCalibration = viewModel::cancelWechatCalibration,
        onDismissError = viewModel::dismissError,
    )
}

/** 适老设置与帮助页；所有主要交互目标至少 56dp。 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    wechatSampleCaptureState: WechatSampleCaptureUiState,
    wechatCalibrationProfiles: WechatCalibrationProfilesUiState,
    wechatCalibrationCapture: WechatCalibrationCaptureUiState,
    onBack: () -> Unit,
    onSpeechRateChanged: (SpeechRatePreference) -> Unit,
    onSpeechVolumeChanged: (SpeechVolumePreference) -> Unit,
    onFontLevelChanged: (FontLevel) -> Unit,
    onHighContrastChanged: (Boolean) -> Unit,
    onOpenHelp: () -> Unit,
    onOpenTaskHistoryDeletion: () -> Unit,
    onOpenAccountClosure: () -> Unit,
    onRequestRoutineCommandDeletion: () -> Unit,
    onCancelRoutineCommandDeletion: () -> Unit,
    onConfirmRoutineCommandDeletion: () -> Unit,
    onDismissRoutineCommandDeletionResult: () -> Unit,
    onRefreshRoutineCommands: () -> Unit,
    onOpenAppPermissionSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onStartWechatSampleCapture: (WechatSampleCaptureTarget) -> Unit,
    onCopyWechatSampleResults: (String) -> Unit,
    onRemoveWechatCalibrationProfile: (WechatCalibrationProfileKey) -> Unit,
    onStartWechatCalibration: () -> Unit,
    onStartWechatMessageCalibration: () -> Unit,
    onCancelWechatCalibration: () -> Unit,
    onDismissError: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "设置与帮助",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineLarge,
        )

        SettingsSection(title = "离线播报音量") {
            SpeechVolumePreference.entries.forEach { value ->
                ChoiceRow(
                    title = when (value) {
                        SpeechVolumePreference.GENTLE -> "轻柔"
                        SpeechVolumePreference.NORMAL -> "正常音量"
                        SpeechVolumePreference.LOUD -> "响亮"
                    },
                    selected = state.settings.speechVolume == value,
                    onClick = { onSpeechVolumeChanged(value) },
                )
            }
            Text(
                "只调整本应用播报，不会修改手机系统音量。",
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        SettingsSection(title = "离线播报语速") {
            SpeechRatePreference.entries.forEach { value ->
                ChoiceRow(
                    title = when (value) {
                        SpeechRatePreference.SLOW -> "慢速"
                        SpeechRatePreference.NORMAL -> "正常"
                        SpeechRatePreference.FAST -> "快速"
                    },
                    selected = state.settings.speechRate == value,
                    onClick = { onSpeechRateChanged(value) },
                )
            }
        }

        SettingsSection(title = "字体大小") {
            FontLevel.entries.forEach { value ->
                ChoiceRow(
                    title = when (value) {
                        FontLevel.LARGE -> "大字"
                        FontLevel.LARGER -> "更大字"
                    },
                    selected = state.settings.fontLevel == value,
                    onClick = { onFontLevelChanged(value) },
                )
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .semantics { contentDescription = "高对比度开关" }
                .clickable(role = Role.Switch) {
                    onHighContrastChanged(!state.settings.highContrast)
                },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("高对比度", style = MaterialTheme.typography.titleLarge)
                    Text("使用更清楚的黑、白、黄色显示", style = MaterialTheme.typography.bodyLarge)
                }
                Switch(
                    checked = state.settings.highContrast,
                    onCheckedChange = null,
                )
            }
        }

        SettingsSection(title = "方言") {
            Text("武冈话", style = MaterialTheme.typography.titleLarge)
            Text(
                text = if (state.dialectPackageAvailable) {
                    "正式签名武冈话包已安装并通过校验。"
                } else {
                    "正式签名武冈话包尚未安装，方言语音联系保持关闭。"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        SettingsSection(title = "设备与权限状态") {
            val capabilities = state.capabilities
            if (capabilities == null) {
                Text("正在读取设备状态……", style = MaterialTheme.typography.bodyLarge)
            } else {
                CapabilityRow(
                    title = "网络",
                    state = capabilities.network,
                    availableText = "网络可用",
                    unavailableText = "网络不可用",
                )
                CapabilityRow("麦克风", capabilities.microphone)
                CapabilityRow("通知", capabilities.notifications)
                CapabilityRow(
                    title = "受限微信辅助服务",
                    state = capabilities.restrictedWechatAccessibility,
                    availableText = "系统已启用",
                    unavailableText = "系统未启用",
                )
                CapabilityRow(
                    title = "当前账号",
                    state = capabilities.accountSession,
                    availableText = "当前账号已登录",
                    unavailableText = "当前账号未登录",
                )
                CapabilityRow(
                    title = "Android 电池优化",
                    state = capabilities.batteryOptimizationExemption,
                    availableText = "已获得系统豁免",
                    unavailableText = "未获得系统豁免，后台可能受限",
                )
            }
            Text(
                "账号已登录或辅助服务已启用，都不代表当前微信版本可以执行联系动作。",
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                onClick = onOpenAppPermissionSettings,
            ) { Text("管理麦克风与通知") }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                onClick = onOpenAccessibilitySettings,
            ) { Text("打开无障碍设置") }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                onClick = onOpenBatterySettings,
            ) { Text("查看省电设置") }
        }

        WechatSampleCaptureSection(
            state = wechatSampleCaptureState,
            onStart = onStartWechatSampleCapture,
            onCopyResults = onCopyWechatSampleResults,
        )

        WechatCalibrationProfilesSection(
            state = wechatCalibrationProfiles,
            captureState = wechatCalibrationCapture,
            onRemove = onRemoveWechatCalibrationProfile,
            onStartCalibration = onStartWechatCalibration,
            onStartMessageCalibration = onStartWechatMessageCalibration,
            onCancelCalibration = onCancelWechatCalibration,
        )

        RoutineCommandOverviewSection(
            state = state.routineCommands,
            onRefresh = onRefreshRoutineCommands,
        )

        SettingsSection(title = "帮助与隐私") {
            Text(
                "只有在您主动说出联系指令并完成复述确认后，应用才会继续。无法唯一确认时会立即停止。",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                onClick = onOpenHelp,
            ) {
                Text("打开使用帮助")
            }
            RoutineCommandDeletionControls(
                state = state.routineCommandDeletion,
                onRequest = onRequestRoutineCommandDeletion,
                onCancel = onCancelRoutineCommandDeletion,
                onConfirm = onConfirmRoutineCommandDeletion,
                onDismissResult = onDismissRoutineCommandDeletionResult,
            )
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                onClick = onOpenTaskHistoryDeletion,
            ) {
                Text("清除任务录音与历史")
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                onClick = onOpenAccountClosure,
            ) {
                Text("永久注销账号")
            }
        }

        state.errorMessage?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(message, style = MaterialTheme.typography.bodyLarge)
                    Button(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        onClick = onDismissError,
                    ) { Text("知道了") }
                }
            }
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            onClick = onBack,
        ) {
            Text("返回")
        }
    }
}

@Composable
private fun WechatCalibrationProfilesSection(
    state: WechatCalibrationProfilesUiState,
    captureState: WechatCalibrationCaptureUiState,
    onRemove: (WechatCalibrationProfileKey) -> Unit,
    onStartCalibration: () -> Unit,
    onStartMessageCalibration: () -> Unit,
    onCancelCalibration: () -> Unit,
) {
    SettingsSection(title = "微信联系校准档案") {
        Text(state.message, style = MaterialTheme.typography.bodyLarge)
        Text(captureState.message, style = MaterialTheme.typography.bodyLarge)
        Text(
            "校准时请在微信内按屏幕边缘的校准条操作。点位记录会拦截本次触摸，不会把记录点击传给微信。",
            style = MaterialTheme.typography.bodyLarge,
        )
        if (captureState.active) {
            Text(
                "已记录 ${captureState.completedCount}/${captureState.totalCount} 个点位。",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                onClick = onCancelCalibration,
            ) {
                Text("取消本次校准")
            }
        } else {
            Button(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                onClick = onStartCalibration,
            ) {
                Text("校准当前组合的语音/视频通话")
            }
            Button(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                onClick = onStartMessageCalibration,
            ) {
                Text("校准当前组合的原声+文字发送")
            }
        }
        Text(
            "已保存 ${state.profiles.size} 份档案；档案数量不设固定上限。",
            style = MaterialTheme.typography.bodyLarge,
        )
        if (state.profiles.isEmpty()) {
            Text(
                "暂无档案。完成一台手机的当面校准后，会按手机、分辨率、显示参数和微信版本加入这里。",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        state.profiles.forEach { profile ->
            val key = profile.key
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        key.manufacturer + " " + key.model,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "${key.displayWidthPixels}×${key.displayHeightPixels} / " +
                            "${key.densityDpi} dpi / 字体 ${key.fontScalePermille / 10}%",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "Android API ${key.androidSdkInt} / " +
                            if (key.orientation == WechatCalibrationOrientation.PORTRAIT) {
                                "竖屏"
                            } else {
                                "横屏"
                            },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "微信 ${key.wechatVersion}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "已校准：" + listOfNotNull(
                            "语音/视频通话".takeIf { profile.supportsCall },
                            "原声+文字发送".takeIf { profile.supportsMessage },
                        ).joinToString("、"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (key == state.currentFingerprint) {
                        Text("当前组合", style = MaterialTheme.typography.titleMedium)
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        onClick = { onRemove(key) },
                    ) {
                        Text("移除此档案")
                    }
                }
            }
        }
    }
}

@Composable
private fun WechatSampleCaptureSection(
    state: WechatSampleCaptureUiState,
    onStart: (WechatSampleCaptureTarget) -> Unit,
    onCopyResults: (String) -> Unit,
) {
    if (!state.visible) return
    SettingsSection(title = "微信页面只读采集") {
        Text(
            "此入口只在开发测试版显示。请选择页面后手动进入微信，应用不点击、不拨号、不挂断。",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "当前页面：" + state.selectedTarget.displayName + "。" +
                state.selectedTarget.instruction,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(state.message, style = MaterialTheme.typography.bodyLarge)
        state.deviceInfo?.let { device ->
            Text("本机规则组合", style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text(
                    device.manufacturer + " / " + device.model +
                        " / Android API " + device.androidSdk,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        state.wechatVersion?.let { version ->
            Text("当前微信版本：" + version, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            "已完成 " + state.completedSamples + " 次，共需 3 次。",
            style = MaterialTheme.typography.bodyLarge,
        )
        state.pageSignatureSha256?.let { signature ->
            Text(
                state.selectedTarget.displayName + "规则准备编号",
                style = MaterialTheme.typography.titleMedium,
            )
            SelectionContainer {
                Text(signature, style = MaterialTheme.typography.bodyLarge)
            }
            state.nodeCount?.let { count ->
                Text(
                    "页面结构节点数：" + count,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Text(
                "该编号只包含页面结构摘要，不包含微信账号、联系人名称或聊天内容。",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        state.locatorSourceSha256?.let { fingerprint ->
            Text("唯一定位字段脱敏编号", style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text(fingerprint, style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                "该编号只表示资料页中带“微信号”标签的字段位置，不包含微信号原文。",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (state.selectedTarget.requiresLocatorSource && state.complete &&
            state.consistent == true && state.locatorSourceSha256 == null
        ) {
            Text(
                "当前没有取得唯一微信号字段，请不要把昵称、备注、头像或页面位置当作联系人依据。",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (state.pageSignatureSha256 != null || state.locatorSourceSha256 != null) {
            Text(
                "应用不会主动保存或上传这些编号；如你手动复制，系统剪贴板可能继续保留。",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (state.completedTargets.isNotEmpty()) {
            Text("本次已完成页面", style = MaterialTheme.typography.titleMedium)
            state.completedTargets.values.forEach { result ->
                Text(
                    "✓ " + result.target.displayName + "（" +
                        result.target.pageType.name + "），节点 " + result.nodeCount,
                    style = MaterialTheme.typography.bodyLarge,
                )
                SelectionContainer {
                    Text(result.pageSignatureSha256, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        val ruleInputProperties = state.buildWechatRuleInputPropertiesOrNull()
        if (state.completedTargets.size == WechatSampleCaptureTarget.entries.size &&
            ruleInputProperties == null
        ) {
            Text(
                "五类页面虽已采集，但亲友资料页没有取得唯一微信号字段和音视频通话动作，请重新采集亲友资料页。",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (ruleInputProperties != null) {
            Button(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                onClick = { onCopyResults(ruleInputProperties) },
            ) {
                Text("复制五类规则输入")
            }
            Text(
                "复制内容只含手机厂商、型号、Android API、微信版本和五类脱敏摘要；不含微信号、联系人或聊天内容。",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        WechatSampleCaptureTarget.entries.forEach { target ->
            val selected = target == state.selectedTarget
            Button(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                enabled = !state.awaiting,
                onClick = { onStart(target) },
            ) {
                Text(
                    when {
                        state.awaiting && selected -> "正在采集" + target.displayName + "……"
                        target in state.completedTargets -> "重新采集" + target.displayName
                        state.complete && selected -> "重新采集" + target.displayName
                        selected -> "采集" + target.displayName + "第 " +
                            (state.completedSamples + 1) + " 次"
                        else -> "采集" + target.displayName
                    },
                )
            }
        }
    }
}

@Composable
private fun RoutineCommandOverviewSection(
    state: RoutineCommandListUiState,
    onRefresh: () -> Unit,
) {
    SettingsSection(title = "日常指令学习") {
        when (state) {
            RoutineCommandListUiState.Loading -> Text(
                "正在读取已学习的动作……",
                style = MaterialTheme.typography.bodyLarge,
            )

            is RoutineCommandListUiState.Ready -> {
                val overview = state.overview
                Text(
                    "已可靠学习 ${overview.totalCount} 条动作模板。",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "发消息 ${overview.sendMessageCount} 条，语音通话 ${overview.voiceCallCount} 条，视频通话 ${overview.videoCallCount} 条。",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    if (overview.incompatibleCount == 0) {
                        "这些模板只包含动作发音，不保存联系人和消息内容。"
                    } else {
                        "其中 ${overview.incompatibleCount} 条与当前方言包不兼容，不能参与后续识别。"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            RoutineCommandListUiState.Failed -> {
                Text(
                    "暂时无法读取已学习动作，现有语音安全步骤不受影响。",
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    onClick = onRefresh,
                ) { Text("重新读取") }
            }
        }
    }
}

@Composable
private fun RoutineCommandDeletionControls(
    state: RoutineCommandDeletionUiState,
    onRequest: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onDismissResult: () -> Unit,
) {
    when (state) {
        RoutineCommandDeletionUiState.Idle -> OutlinedButton(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            onClick = onRequest,
        ) {
            Text("清除日常指令模板")
        }

        RoutineCommandDeletionUiState.Confirming -> Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "这会清除系统学习到的日常说法，且不能恢复。不会删除联系人称呼，也不会删除确认、取消等安全指令。",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                onClick = onConfirm,
            ) {
                Text("再次确认清除")
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                onClick = onCancel,
            ) {
                Text("取消")
            }
        }

        RoutineCommandDeletionUiState.Submitting -> Button(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            enabled = false,
            onClick = {},
        ) {
            Text("正在清除……")
        }

        is RoutineCommandDeletionUiState.Completed -> Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "已清除 ${state.deletedCount} 条日常指令模板。联系人称呼和安全指令未受影响。",
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                onClick = onDismissResult,
            ) {
                Text("知道了")
            }
        }

        is RoutineCommandDeletionUiState.Failed -> Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(state.message.toChineseUiMessage("设置读取失败，请稍后重试。"), style = MaterialTheme.typography.bodyLarge)
            Button(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                onClick = onRequest,
            ) {
                Text("重新确认")
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                onClick = onDismissResult,
            ) {
                Text("取消")
            }
        }
    }
}

@Composable
private fun CapabilityRow(
    title: String,
    state: CapabilityReadState,
    availableText: String = "已允许",
    unavailableText: String = "未允许",
) {
    val (symbol, description) = when (state) {
        CapabilityReadState.AVAILABLE -> "✓" to availableText
        CapabilityReadState.UNAVAILABLE -> "!" to unavailableText
        CapabilityReadState.UNKNOWN -> "?" to "暂时无法读取"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title，$description"
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(symbol, style = MaterialTheme.typography.headlineSmall)
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(description, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
            )
            content()
        }
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) { contentDescription = title },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}
