package com.aifriend.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aifriend.feature.wechat.WechatDebugNodeProbe

/** ADB shell 专用 Debug 预检入口；Manifest 使用 DUMP 权限拒绝普通应用调用。 */
class WechatNodeProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) {
            resultData = "INVALID_ACTION"
            return
        }
        val stage = intent.getStringExtra(EXTRA_STAGE).orEmpty()
        val result = if (stage == CLEAR_STAGE) {
            WechatDebugNodeProbe.clear(context.applicationContext)
        } else {
            WechatDebugNodeProbe.capture(context.applicationContext, stage)
        }
        resultData = result.name
    }

    private companion object {
        const val ACTION = "com.aifriend.debug.WECHAT_NODE_PROBE"
        const val EXTRA_STAGE = "stage"
        const val CLEAR_STAGE = "CLEAR"
    }
}
