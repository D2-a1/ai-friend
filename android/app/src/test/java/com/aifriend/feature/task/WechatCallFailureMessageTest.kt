package com.aifriend.feature.task

import com.aifriend.feature.wechat.WechatSemanticCallExecutionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class WechatCallFailureMessageTest {

    @Test
    fun `微信执行窗口无页面事件时明确提示主微信而不是继续等待`() {
        assertEquals(
            "未检测到可由小友操作的主微信页面；如果打开的是微信分身，请切换到主微信后重试，语音通话没有发起",
            WechatSemanticCallExecutionStatus.WINDOW_EXPIRED
                .wechatCallFailureMessage("语音通话"),
        )
        assertEquals(
            "未检测到可由小友操作的主微信页面；如果打开的是微信分身，请切换到主微信后重试，视频通话没有发起",
            WechatSemanticCallExecutionStatus.CALIBRATED_WECHAT_NOT_FOREGROUND
                .wechatCallFailureMessage("视频通话"),
        )
    }

    @Test
    fun `其他安全校验失败仍保持失败关闭且不误报分身`() {
        assertEquals(
            "没有安全打开语音通话，不会自动重试",
            WechatSemanticCallExecutionStatus.PROFILE_TARGET_MISMATCH
                .wechatCallFailureMessage("语音通话"),
        )
    }
}
