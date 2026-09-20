package com.aifriend.feature.wechat

import java.time.Duration
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 当面校准界面状态；不包含微信号、联系人或任何页面文字。 */
data class WechatCalibrationCaptureUiState(
    val active: Boolean = false,
    val accessibilityReady: Boolean = false,
    val currentTarget: WechatCalibrationTarget = WechatCalibrationTarget.HOME_SEARCH,
    val completedCount: Int = 0,
    val totalCount: Int = WechatCalibrationPurpose.CALL.targets.size,
    val purpose: WechatCalibrationPurpose = WechatCalibrationPurpose.CALL,
    val message: String = "尚未开始本机微信通话校准。",
)

/** 辅助服务当前可消费的一次校准请求。 */
data class WechatCalibrationCaptureRequest(
    val key: WechatCalibrationProfileKey,
    val target: WechatCalibrationTarget,
    val openedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
) {
    override fun toString(): String =
        "WechatCalibrationCaptureRequest(key=$key, target=$target, " +
            "openedAt=$openedAt, expiresAt=$expiresAt)"
}

enum class WechatCalibrationRecordResult {
    SAVED_NEXT,
    COMPLETED,
    STALE_REQUEST,
    FAILED,
}

interface WechatCalibrationCaptureCoordinator {
    val state: StateFlow<WechatCalibrationCaptureUiState>

    fun start(): Boolean

    fun start(purpose: WechatCalibrationPurpose): Boolean =
        if (purpose == WechatCalibrationPurpose.CALL) start() else false

    fun cancel()

    fun failToOpenWechat()
}

/**
 * 单次当面校准会话。
 *
 * 会话只接收用户明确点下的屏幕坐标。每次记录前都重新核对完整显示指纹；方向、分辨率、
 * 字体缩放或微信版本发生变化即丢弃整次会话。当前所需目标全部完成后才原子写入档案列表。
 */
@Singleton
class AndroidWechatCalibrationCaptureCoordinator @Inject constructor(
    private val runtimeVersionProvider: WechatRuntimeVersionProvider,
    private val fingerprintProvider: WechatCalibrationFingerprintProvider,
    private val profileRegistry: WechatCalibrationProfileRegistry,
) : WechatCalibrationCaptureCoordinator {
    private val mutableState = MutableStateFlow(WechatCalibrationCaptureUiState())
    private var session: Session? = null

    override val state: StateFlow<WechatCalibrationCaptureUiState> = mutableState.asStateFlow()

    @Synchronized
    override fun start(): Boolean = start(WechatCalibrationPurpose.CALL)

    @Synchronized
    override fun start(purpose: WechatCalibrationPurpose): Boolean {
        if (!mutableState.value.accessibilityReady) {
            mutableState.value = mutableState.value.copy(
                message = "请先开启受限微信辅助服务，再开始校准。",
            )
            return false
        }
        if (session != null) return false
        val key = runCatching {
            runtimeVersionProvider.readCurrentVersion()?.let(fingerprintProvider::current)
        }.getOrNull()
        if (key == null) {
            mutableState.value = mutableState.value.copy(
                message = "无法读取当前微信版本或显示参数，未开始校准。",
            )
            return false
        }
        val existingProfile = runCatching { profileRegistry.findExact(key) }.getOrNull()
        val targets = if (
            purpose == WechatCalibrationPurpose.CALL &&
            existingProfile?.supportsLegacyCallWithoutChatAvatar == true
        ) {
            purpose.targets.filterNot { it in existingProfile.points }
        } else {
            purpose.targets
        }
        val now = OffsetDateTime.now()
        session = Session(
            key = key,
            purpose = purpose,
            targets = targets,
            targetIndex = 0,
            points = linkedMapOf(),
            openedAt = now,
            expiresAt = now.plus(SESSION_LIFETIME),
        )
        publishActive(checkNotNull(session))
        return true
    }

    @Synchronized
    override fun cancel() {
        session = null
        mutableState.value = mutableState.value.copy(
            active = false,
            completedCount = 0,
            currentTarget = WechatCalibrationTarget.HOME_SEARCH,
            message = "本次校准已取消，没有保存不完整档案。",
        )
    }

    @Synchronized
    override fun failToOpenWechat() {
        failLocked("没有打开微信，本次校准已取消。")
    }

    @Synchronized
    fun updateAccessibilityReady(ready: Boolean) {
        if (!ready && session != null) {
            failLocked("微信辅助服务已断开，本次校准未保存。")
            return
        }
        mutableState.value = mutableState.value.copy(accessibilityReady = ready)
    }

    @Synchronized
    fun activeRequest(
        packageName: String,
        now: OffsetDateTime,
    ): WechatCalibrationCaptureRequest? {
        if (packageName != WechatSemanticCallContract.WECHAT_PACKAGE) return null
        val current = session ?: return null
        if (!current.expiresAt.isAfter(now)) {
            failLocked("校准等待时间已结束，没有保存不完整档案。")
            return null
        }
        return WechatCalibrationCaptureRequest(
            key = current.key,
            target = current.targets[current.targetIndex],
            openedAt = current.openedAt,
            expiresAt = current.expiresAt,
        )
    }

    @Synchronized
    fun record(
        request: WechatCalibrationCaptureRequest,
        rawX: Int,
        rawY: Int,
        now: OffsetDateTime,
    ): WechatCalibrationRecordResult {
        val current = session ?: return WechatCalibrationRecordResult.STALE_REQUEST
        if (current.targets[current.targetIndex] != request.target ||
            current.key != request.key
        ) {
            return WechatCalibrationRecordResult.STALE_REQUEST
        }
        if (!current.expiresAt.isAfter(now) || now.isBefore(current.openedAt)) {
            failLocked("校准等待时间已结束，没有保存不完整档案。")
            return WechatCalibrationRecordResult.FAILED
        }
        if (runCatching { fingerprintProvider.current(current.key.wechatVersion) }.getOrNull() !=
            current.key
        ) {
            failLocked("显示参数或微信版本已变化，本次校准未保存，请按当前状态重新校准。")
            return WechatCalibrationRecordResult.FAILED
        }
        val point = WechatNormalizedCalibrationPoint.fromPixels(
            x = rawX,
            y = rawY,
            widthPixels = current.key.displayWidthPixels,
            heightPixels = current.key.displayHeightPixels,
        ) ?: run {
            failLocked("记录位置超出当前屏幕，本次校准未保存。")
            return WechatCalibrationRecordResult.FAILED
        }
        current.points[request.target] = point
        val nextIndex = current.targetIndex + 1
        if (nextIndex < current.targets.size) {
            session = current.copy(targetIndex = nextIndex)
            publishActive(checkNotNull(session))
            return WechatCalibrationRecordResult.SAVED_NEXT
        }
        val existingPoints = runCatching { profileRegistry.findExact(current.key)?.points }
            .getOrNull()
            .orEmpty()
        val profile = runCatching {
            WechatCalibrationProfile(
                key = current.key,
                points = existingPoints + current.points.toMap(),
                updatedAtEpochMillis = now.toInstant().toEpochMilli(),
            )
        }.getOrNull()
        if (profile == null ||
            !runCatching { profileRegistry.upsert(profile) }.getOrDefault(false)
        ) {
            failLocked("完整档案保存失败，请返回应用后重新校准。")
            return WechatCalibrationRecordResult.FAILED
        }
        session = null
        mutableState.value = mutableState.value.copy(
            active = false,
            completedCount = current.targets.size,
            currentTarget = current.targets.last(),
            purpose = current.purpose,
            totalCount = current.targets.size,
            message = if (current.purpose == WechatCalibrationPurpose.MESSAGE) {
                "当前组合的消息发送校准完成，校准内容未发送。"
            } else {
                "当前组合通话校准完成，已加入多设备档案列表。"
            },
        )
        return WechatCalibrationRecordResult.COMPLETED
    }

    @Synchronized
    fun failOverlay() {
        failLocked("无法显示校准条，本次校准未保存。")
    }

    @Synchronized
    fun interrupt() {
        if (session != null) {
            failLocked("微信辅助服务已中断，本次校准未保存。")
        }
    }

    private fun publishActive(value: Session) {
        mutableState.value = mutableState.value.copy(
            active = true,
            currentTarget = value.targets[value.targetIndex],
            completedCount = value.points.size,
            totalCount = value.targets.size,
            purpose = value.purpose,
            message = "请在微信中按校准条提示完成当前点位。",
        )
    }

    private fun failLocked(message: String) {
        session = null
        mutableState.value = mutableState.value.copy(
            active = false,
            completedCount = 0,
            currentTarget = WechatCalibrationTarget.HOME_SEARCH,
            message = message,
        )
    }

    private data class Session(
        val key: WechatCalibrationProfileKey,
        val purpose: WechatCalibrationPurpose,
        val targets: List<WechatCalibrationTarget>,
        val targetIndex: Int,
        val points: LinkedHashMap<WechatCalibrationTarget, WechatNormalizedCalibrationPoint>,
        val openedAt: OffsetDateTime,
        val expiresAt: OffsetDateTime,
    )

    private companion object {
        val SESSION_LIFETIME: Duration = Duration.ofMinutes(15)
    }
}

val WechatCalibrationTarget.calibrationDisplayName: String
    get() = when (this) {
        WechatCalibrationTarget.HOME_SEARCH -> "微信首页搜索入口"
        WechatCalibrationTarget.GLOBAL_SEARCH_INPUT -> "搜索页输入框"
        WechatCalibrationTarget.GLOBAL_SEARCH_PASTE -> "输入框长按后的粘贴按钮"
        WechatCalibrationTarget.SEARCH_RESULT -> "唯一联系人搜索结果"
        WechatCalibrationTarget.CHAT_CONTACT_AVATAR -> "聊天页对方头像"
        WechatCalibrationTarget.CHAT_INFO_MENU -> "聊天页右上角更多选项"
        WechatCalibrationTarget.CHAT_INFO_CONTACT_AVATAR -> "聊天信息页左上方亲友头像"
        WechatCalibrationTarget.CONTACT_PROFILE_CALL_ENTRY -> "资料页音视频通话入口"
        WechatCalibrationTarget.CALL_CHOICE_VOICE -> "通话选择页语音通话"
        WechatCalibrationTarget.CALL_CHOICE_VIDEO -> "通话选择页视频通话"
        WechatCalibrationTarget.SHARE_SEARCH_ENTRY -> "分享页搜索入口"
        WechatCalibrationTarget.SHARE_SEARCH_INPUT -> "分享页搜索输入框"
        WechatCalibrationTarget.SHARE_SEARCH_PASTE -> "分享页粘贴按钮"
        WechatCalibrationTarget.SHARE_SEARCH_RESULT -> "分享页唯一联系人结果"
        WechatCalibrationTarget.SHARE_SEND_CONFIRM -> "分享确认页发送按钮"
    }
