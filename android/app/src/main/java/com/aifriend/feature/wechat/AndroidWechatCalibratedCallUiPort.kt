package com.aifriend.feature.wechat

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.os.PersistableBundle
import com.aifriend.feature.guardian.GuardianWechatCallAudioCoordinator
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 校准坐标执行的 Android 适配器。
 *
 * 每个手势只提交一次；剪贴板只在搜索步骤短时持有微信号并标记敏感，粘贴后立即清空。
 */
class AndroidWechatCalibratedCallUiPort(
    private val service: AccessibilityService,
    private val runtimeVersionProvider: WechatRuntimeVersionProvider,
    private val fingerprintProvider: WechatCalibrationFingerprintProvider,
    private val audioCoordinator: GuardianWechatCallAudioCoordinator,
) : WechatCalibratedCallUiPort {
    private val clipboardManager: ClipboardManager? =
        service.getSystemService(ClipboardManager::class.java)

    override fun currentTime(): OffsetDateTime = OffsetDateTime.now()

    override fun currentFingerprint(): WechatCalibrationProfileKey? {
        val version = runCatching { runtimeVersionProvider.readCurrentVersion() }.getOrNull()
            ?: return null
        return runCatching { fingerprintProvider.current(version) }.getOrNull()
    }

    override fun isWechatForeground(): Boolean =
        runCatching {
            service.rootInActiveWindow?.packageName?.toString() ==
                WechatSemanticCallContract.WECHAT_PACKAGE
        }.getOrDefault(false)

    override suspend fun tap(point: WechatCalibrationPixelPoint): Boolean =
        dispatch(point, TAP_DURATION)

    override suspend fun longPress(point: WechatCalibrationPixelPoint): Boolean =
        dispatch(point, LONG_PRESS_DURATION)

    override fun setSensitiveSearchClipboard(value: CharArray): Boolean {
        if (value.isEmpty() || value.any { it == '\u0000' }) return false
        val manager = clipboardManager ?: return false
        return runCatching {
            val clip = ClipData.newPlainText(CLIP_LABEL, String(value))
            clip.description.extras = PersistableBundle().apply {
                putBoolean(SENSITIVE_CLIP_EXTRA, true)
            }
            manager.setPrimaryClip(clip)
            true
        }.getOrDefault(false)
    }

    override fun clearSearchClipboard() {
        runCatching { clipboardManager?.clearPrimaryClip() }
    }

    override suspend fun waitForUi(duration: Duration) {
        val millis = duration.toMillis()
        if (millis > 0) delay(millis)
    }

    override suspend fun releaseAudioBeforeCall(): Boolean =
        audioCoordinator.releaseBeforeCall()

    private suspend fun dispatch(
        point: WechatCalibrationPixelPoint,
        durationMillis: Long,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        fun finish(value: Boolean) {
            if (completed.compareAndSet(false, true) && continuation.isActive) {
                continuation.resume(value)
            }
        }
        continuation.invokeOnCancellation { completed.set(true) }
        val path = Path().apply { moveTo(point.x.toFloat(), point.y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMillis))
            .build()
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                finish(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                finish(false)
            }
        }
        val accepted = runCatching {
            service.dispatchGesture(gesture, callback, null)
        }.getOrDefault(false)
        if (!accepted) {
            finish(false)
        }
    }

    private companion object {
        const val TAP_DURATION = 80L
        const val LONG_PRESS_DURATION = 650L
        const val CLIP_LABEL = "AI好友临时搜索"
        const val SENSITIVE_CLIP_EXTRA = "android.content.extra.IS_SENSITIVE"
    }
}
