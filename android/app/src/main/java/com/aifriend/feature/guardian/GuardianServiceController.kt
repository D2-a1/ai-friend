package com.aifriend.feature.guardian

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.aifriend.service.GuardianForegroundService

/**
 * 可见页面发起的守护服务命令。
 *
 * 本对象不保存开启状态；进程重建后不会自动调用 [startFromVisiblePage]。
 */
object GuardianServiceController {
    fun startFromVisiblePage(context: Context) {
        val intent = Intent(context, GuardianForegroundService::class.java)
            .setAction(GuardianForegroundService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context): Boolean =
        context.stopService(Intent(context, GuardianForegroundService::class.java))
}
