package com.aifriend.feature.wechat

import android.content.Context
import android.os.Build
import com.aifriend.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Debug 采样允许人工选择的有限微信页面；枚举值可直接对应规则矩阵页面类型。 */
enum class WechatSampleCaptureTarget(
    val pageType: WechatPageType,
    val displayName: String,
    val instruction: String,
    val ruleInputPropertyName: String,
    val requiresLocatorSource: Boolean,
) {
    CONTACT_PROFILE(
        WechatPageType.CONTACT_PROFILE,
        "亲友资料页",
        "请在五秒内进入同一位亲友的资料页。",
        "contactProfileSha256",
        true,
    ),
    VOICE_CALL_CONFIRMATION(
        WechatPageType.VOICE_CALL_CONFIRMATION,
        "语音通话选择页",
        "请手动进入显示“语音通话”的通话选择页，不要点击通话。",
        "voiceCallConfirmationSha256",
        false,
    ),
    VOICE_CALL_ACTIVE(
        WechatPageType.VOICE_CALL_ACTIVE,
        "语音通话中页",
        "请在已明确发起的语音通话页面停留；应用不会自动拨号或挂断。",
        "voiceCallActiveSha256",
        false,
    ),
    VIDEO_CALL_CONFIRMATION(
        WechatPageType.VIDEO_CALL_CONFIRMATION,
        "视频通话选择页",
        "请手动进入显示“视频通话”的通话选择页，不要点击通话。",
        "videoCallConfirmationSha256",
        false,
    ),
    VIDEO_CALL_ACTIVE(
        WechatPageType.VIDEO_CALL_ACTIVE,
        "视频通话中页",
        "请在已明确发起的视频通话页面停留；应用不会自动拨号或挂断。",
        "videoCallActiveSha256",
        false,
    ),
}

/** 规则与运行时必须精确匹配的非唯一设备事实。 */
data class WechatSampleCaptureDeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidSdk: Int,
)

data class WechatSampleCaptureResult(
    val target: WechatSampleCaptureTarget,
    val wechatVersion: String,
    val pageSignatureSha256: String,
    val nodeCount: Int,
    val locatorSourceSha256: String? = null,
)

/** Debug 版微信页面只读采集的有限界面状态。 */
data class WechatSampleCaptureUiState(
    val visible: Boolean,
    val deviceInfo: WechatSampleCaptureDeviceInfo? = null,
    val selectedTarget: WechatSampleCaptureTarget = WechatSampleCaptureTarget.CONTACT_PROFILE,
    val completedSamples: Int = 0,
    val awaiting: Boolean = false,
    val complete: Boolean = false,
    val consistent: Boolean? = null,
    val wechatVersion: String? = null,
    val pageSignatureSha256: String? = null,
    val nodeCount: Int? = null,
    val locatorSourceSha256: String? = null,
    val locatorSourceCount: Int? = null,
    val locatorSourceConsistent: Boolean? = null,
    val completedTargets: Map<WechatSampleCaptureTarget, WechatSampleCaptureResult> = emptyMap(),
    val message: String = "",
)

/**
 * 五类采样全部通过后生成可直接粘贴到仓库外 properties 的动态字段。
 *
 * 不包含 Debug 包名、版本、证书或发布构建身份，避免把测试身份误签入正式规则。
 */
fun WechatSampleCaptureUiState.buildWechatRuleInputPropertiesOrNull(): String? {
    val device = deviceInfo ?: return null
    if (!device.manufacturer.isSafeRuleDeviceFact() ||
        !device.model.isSafeRuleDeviceFact() ||
        device.androidSdk !in 29..100
    ) {
        return null
    }
    val results = WechatSampleCaptureTarget.entries.map { target ->
        completedTargets[target]?.takeIf { result -> result.target == target } ?: return null
    }
    val versions = results.map { it.wechatVersion }.distinct()
    if (versions.size != 1 || !versions.single().matches(WECHAT_VERSION_TOKEN)) return null
    val contactProfile = completedTargets[WechatSampleCaptureTarget.CONTACT_PROFILE]
        ?: return null
    if (contactProfile.locatorSourceSha256?.matches(SHA256_HEX_TOKEN) != true) return null
    if (results.any { !it.pageSignatureSha256.matches(SHA256_HEX_TOKEN) }) return null

    return buildString {
        append("deviceManufacturer=")
        append(device.manufacturer.toJavaPropertiesValue())
        append('\n')
        append("deviceModel=")
        append(device.model.toJavaPropertiesValue())
        append('\n')
        append("androidSdk=")
        append(device.androidSdk)
        append('\n')
        append("wechatVersion=")
        append(versions.single().toJavaPropertiesValue())
        results.forEach { result ->
            append('\n')
            append(result.target.ruleInputPropertyName)
            append('=')
            append(result.pageSignatureSha256)
        }
    }
}

private fun String.isSafeRuleDeviceFact(): Boolean =
    isNotBlank() && this == trim() && length <= 100 &&
        none { character ->
            Character.isISOControl(character) ||
                Character.getType(character) == Character.FORMAT.toInt()
        }

private fun String.toJavaPropertiesValue(): String = buildString(length) {
    this@toJavaPropertiesValue.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            ' ' -> append("\\ ")
            '=' -> append("\\=")
            ':' -> append("\\:")
            '#' -> append("\\#")
            '!' -> append("\\!")
            else -> append(character)
        }
    }
}

private val WECHAT_VERSION_TOKEN = Regex("[^\\s:]{1,100}")
private val SHA256_HEX_TOKEN = Regex("[0-9a-f]{64}")

/** 设置页使用的微信页面只读采集端口。 */
interface WechatSampleCaptureCoordinator {
    val state: StateFlow<WechatSampleCaptureUiState>

    fun begin(): Boolean

    fun begin(target: WechatSampleCaptureTarget): Boolean = begin()

    fun refresh()

    fun failToOpenWechat()
}

/** 当前五秒采集窗口；只含微信版本，不含页面正文或节点内容。 */
data class WechatSampleCaptureRequest(
    val target: WechatSampleCaptureTarget,
    val wechatVersion: String,
    val openedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
)

/**
 * 三次微信页面结构摘要采集状态机。
 *
 * 只在内存中保存三次 SHA-256、采集次数和微信版本；完成、超时或进程退出后不会自动执行操作。
 */
class WechatSampleCaptureSession(
    private val enabled: Boolean,
    private val deviceInfo: WechatSampleCaptureDeviceInfo? = null,
) {
    private val signatures = ArrayList<String>(REQUIRED_SAMPLE_COUNT)
    private val nodeCounts = ArrayList<Int>(REQUIRED_SAMPLE_COUNT)
    private val locatorSourceHashes = ArrayList<Set<String>>(REQUIRED_SAMPLE_COUNT)
    private val completedTargets = LinkedHashMap<
        WechatSampleCaptureTarget,
        WechatSampleCaptureResult,
    >()
    private var selectedTarget = WechatSampleCaptureTarget.CONTACT_PROFILE
    private var expectedWechatVersion: String? = null
    private var request: WechatSampleCaptureRequest? = null
    private val mutableState = MutableStateFlow(initialState())

    val state: StateFlow<WechatSampleCaptureUiState> = mutableState.asStateFlow()

    @Synchronized
    fun begin(wechatVersion: String, now: OffsetDateTime): Boolean {
        return begin(WechatSampleCaptureTarget.CONTACT_PROFILE, wechatVersion, now)
    }

    @Synchronized
    fun begin(
        target: WechatSampleCaptureTarget,
        wechatVersion: String,
        now: OffsetDateTime,
    ): Boolean {
        if (!enabled) return false
        expireLocked(now)
        if (request != null) return false
        if (selectedTarget != target || state.value.complete) resetRoundLocked(target)
        if (!wechatVersion.matches(VERSION_TOKEN)) {
            failLocked("无法读取当前微信版本，请确认微信已经安装。")
            return false
        }
        val expected = expectedWechatVersion
        if (expected != null && expected != wechatVersion) {
            resetAllLocked()
            failLocked("微信版本发生变化，请重新完成三次采集。")
            return false
        }
        expectedWechatVersion = wechatVersion
        request = WechatSampleCaptureRequest(
            target = target,
            wechatVersion = wechatVersion,
            openedAt = now,
            expiresAt = now.plus(CAPTURE_WINDOW),
        )
        mutableState.value = WechatSampleCaptureUiState(
            visible = true,
            deviceInfo = deviceInfo,
            selectedTarget = target,
            completedSamples = signatures.size,
            awaiting = true,
            wechatVersion = wechatVersion,
            completedTargets = completedTargets.toMap(),
            message = "已打开五秒只读采集。" + target.instruction,
        )
        return true
    }

    @Synchronized
    fun activeRequest(packageName: String, now: OffsetDateTime): WechatSampleCaptureRequest? {
        expireLocked(now)
        return request?.takeIf { packageName == WECHAT_PACKAGE }
    }

    @Synchronized
    fun publish(
        sample: WechatPageStructureSample,
        wechatVersion: String,
        now: OffsetDateTime,
    ) {
        expireLocked(now)
        val current = request ?: return
        if (wechatVersion != current.wechatVersion ||
            !sample.signatureSha256.matches(SHA256_HEX) ||
            sample.nodeCount !in 1..MAXIMUM_SAMPLE_NODE_COUNT ||
            sample.locatorSourceSha256s.size > MAXIMUM_LOCATOR_SOURCES ||
            sample.locatorSourceSha256s.any { !it.matches(SHA256_HEX) } ||
            sample.capturedAt.isBefore(current.openedAt) ||
            sample.capturedAt.isAfter(current.expiresAt)
        ) {
            return
        }
        request = null
        signatures += sample.signatureSha256
        nodeCounts += sample.nodeCount
        locatorSourceHashes += sample.locatorSourceSha256s.toSet()
        val complete = signatures.size == REQUIRED_SAMPLE_COUNT
        val structureConsistent = complete &&
            signatures.distinct().size == 1 &&
            nodeCounts.distinct().size == 1
        val sourceCountsConsistent = complete &&
            locatorSourceHashes.map { it.size }.distinct().size == 1
        val locatorSourceConsistent = current.target.requiresLocatorSource &&
            sourceCountsConsistent &&
            locatorSourceHashes.all { it.size == 1 } &&
            locatorSourceHashes.map { it.single() }.distinct().size == 1
        val pageSignature = signatures.firstOrNull().takeIf { structureConsistent }
        val nodeCount = nodeCounts.firstOrNull().takeIf { structureConsistent }
        val locatorSource = locatorSourceHashes.firstOrNull()?.singleOrNull()
            ?.takeIf { locatorSourceConsistent }
        val requiredEvidenceConsistent =
            !current.target.requiresLocatorSource || locatorSource != null
        if (complete && pageSignature != null && nodeCount != null &&
            requiredEvidenceConsistent
        ) {
            completedTargets[current.target] = WechatSampleCaptureResult(
                target = current.target,
                wechatVersion = current.wechatVersion,
                pageSignatureSha256 = pageSignature,
                nodeCount = nodeCount,
                locatorSourceSha256 = locatorSource,
            )
        }
        mutableState.value = WechatSampleCaptureUiState(
            visible = true,
            deviceInfo = deviceInfo,
            selectedTarget = current.target,
            completedSamples = signatures.size,
            complete = complete,
            consistent = structureConsistent.takeIf { complete },
            wechatVersion = current.wechatVersion,
            pageSignatureSha256 = pageSignature,
            nodeCount = nodeCount,
            locatorSourceSha256 = locatorSource,
            locatorSourceCount = locatorSourceHashes.firstOrNull()?.size
                ?.takeIf { current.target.requiresLocatorSource && sourceCountsConsistent },
            locatorSourceConsistent = locatorSourceConsistent
                .takeIf { complete && current.target.requiresLocatorSource },
            completedTargets = completedTargets.toMap(),
            message = when {
                !complete -> "已完成第 " + signatures.size + " 次，请返回后继续采集。"
                !structureConsistent -> "三次页面结构不一致，请保持同一页面后重新采集。"
                !current.target.requiresLocatorSource ->
                    "三次页面结构一致，已生成" + current.target.displayName + "脱敏编号。"
                locatorSourceConsistent -> "三次页面结构与唯一定位来源一致，已生成脱敏准备编号。"
                else -> "页面结构一致，但没有取得三次一致的唯一微信号字段。"
            },
        )
    }

    @Synchronized
    fun refresh(now: OffsetDateTime) {
        expireLocked(now)
    }

    @Synchronized
    fun failToOpenWechat() {
        if (!enabled) return
        request = null
        failLocked("无法打开微信，请确认微信已经安装并可以正常启动。")
    }

    @Synchronized
    fun interrupt() {
        if (request == null) return
        request = null
        failLocked("只读采集已中断，请重新开始本次采集。")
    }

    private fun expireLocked(now: OffsetDateTime) {
        val current = request ?: return
        if (now.isBefore(current.openedAt) || !current.expiresAt.isAfter(now)) {
            request = null
            failLocked("五秒内没有取得页面结构，请确认辅助服务已启用后重试。")
        }
    }

    private fun resetRoundLocked(target: WechatSampleCaptureTarget) {
        request = null
        selectedTarget = target
        completedTargets.remove(target)
        signatures.clear()
        nodeCounts.clear()
        locatorSourceHashes.clear()
        mutableState.value = initialState(target)
    }

    private fun resetAllLocked() {
        completedTargets.clear()
        expectedWechatVersion = null
        resetRoundLocked(WechatSampleCaptureTarget.CONTACT_PROFILE)
    }

    private fun failLocked(message: String) {
        mutableState.value = WechatSampleCaptureUiState(
            visible = true,
            deviceInfo = deviceInfo,
            selectedTarget = selectedTarget,
            completedSamples = signatures.size,
            wechatVersion = expectedWechatVersion,
            completedTargets = completedTargets.toMap(),
            message = message,
        )
    }

    private fun initialState(
        target: WechatSampleCaptureTarget = selectedTarget,
    ): WechatSampleCaptureUiState = WechatSampleCaptureUiState(
        visible = enabled,
        deviceInfo = deviceInfo,
        selectedTarget = target,
        completedTargets = completedTargets.toMap(),
        message = if (enabled) {
            "仅采集页面结构摘要，不读取文字、不截图、不点击。"
        } else {
            ""
        },
    )

    private companion object {
        val CAPTURE_WINDOW: Duration = Duration.ofSeconds(5)
        val VERSION_TOKEN = Regex("[^\\s:]{1,100}")
        val SHA256_HEX = Regex("[0-9a-f]{64}")
        const val REQUIRED_SAMPLE_COUNT = 3
        const val MAXIMUM_SAMPLE_NODE_COUNT = 256
        const val MAXIMUM_LOCATOR_SOURCES = 8
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}

/** Android Debug 版采集协调器；Release 构建开关固定关闭。 */
@Singleton
class AndroidWechatSampleCaptureCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : WechatSampleCaptureCoordinator {
    private val session = WechatSampleCaptureSession(
        enabled = BuildConfig.WECHAT_SAMPLE_CAPTURE_ENABLED,
        deviceInfo = if (BuildConfig.WECHAT_SAMPLE_CAPTURE_ENABLED) {
            WechatSampleCaptureDeviceInfo(
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                androidSdk = Build.VERSION.SDK_INT,
            )
        } else {
            null
        },
    )

    override val state: StateFlow<WechatSampleCaptureUiState> = session.state

    override fun begin(): Boolean {
        return begin(WechatSampleCaptureTarget.CONTACT_PROFILE)
    }

    override fun begin(target: WechatSampleCaptureTarget): Boolean {
        if (!BuildConfig.WECHAT_SAMPLE_CAPTURE_ENABLED) return false
        val wechatVersion = readWechatVersion() ?: run {
            session.failToOpenWechat()
            return false
        }
        return session.begin(target, wechatVersion, OffsetDateTime.now())
    }

    override fun refresh() {
        session.refresh(OffsetDateTime.now())
    }

    override fun failToOpenWechat() {
        session.failToOpenWechat()
    }

    fun activeRequest(
        packageName: String,
        now: OffsetDateTime,
    ): WechatSampleCaptureRequest? = session.activeRequest(packageName, now)

    fun publish(
        sample: WechatPageStructureSample,
        now: OffsetDateTime,
    ) {
        val version = readWechatVersion() ?: return
        session.publish(sample, version, now)
    }

    fun interrupt() {
        session.interrupt()
    }

    @Suppress("DEPRECATION")
    private fun readWechatVersion(): String? = runCatching {
        context.packageManager.getPackageInfo(WECHAT_PACKAGE, 0).versionName
    }.getOrNull()

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}
