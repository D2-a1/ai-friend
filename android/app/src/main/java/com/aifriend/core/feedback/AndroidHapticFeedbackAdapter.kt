package com.aifriend.core.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 使用 Android 系统震动器播放有限的短状态提示。
 *
 * 不循环、不改系统设置；设备无震动器、系统拒绝或震动异常时静默停止，
 * 不影响主业务状态。
 *
 * @author codex
 * @since 2026-08-22
 */
@Singleton
class AndroidHapticFeedbackAdapter @Inject constructor(
    @ApplicationContext context: Context,
) : HapticFeedbackPort {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    private val monitor = Any()

    override fun emit(cue: HapticCue) {
        synchronized(monitor) {
            runCatching { vibrator.cancel() }
            val pattern = HapticPatternRegistry.pattern(cue) ?: return
            if (!runCatching { vibrator.hasVibrator() }.getOrDefault(false)) return
            runCatching {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, NO_REPEAT))
            }
        }
    }

    override fun cancel() {
        synchronized(monitor) {
            runCatching { vibrator.cancel() }
        }
    }

    private companion object {
        const val NO_REPEAT = -1
    }
}
