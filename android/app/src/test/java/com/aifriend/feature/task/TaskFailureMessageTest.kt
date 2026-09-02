package com.aifriend.feature.task

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskFailureMessageTest {

    @Test
    fun `同为 409 的安全模板错误必须显示不同处理方式`() {
        assertEquals(
            "服务端没有完整四类安全指令，请打开录制安全指令页面核对",
            taskFailureMessage(409, "SAFETY_COMMAND_REQUIRED"),
        )
        assertEquals(
            "服务端四类安全指令与当前本机识别版本不一致，请停止重复录制并更新服务端",
            taskFailureMessage(409, "TEMPLATE_INCOMPATIBLE"),
        )
        assertEquals(
            "当前任务与服务器状态冲突，请返回首页后重新开始",
            taskFailureMessage(409, "SESSION_CONFLICT"),
        )
    }

    @Test
    fun `旧服务没有错误码时仍按 HTTP 状态安全回落`() {
        assertEquals(
            "任务状态或个人安全指令已经变化，请重新说",
            taskFailureMessage(409, null),
        )
        assertEquals(
            "请先完成当前语音用途授权",
            taskFailureMessage(403, null),
        )
    }

    @Test
    fun `非 409 稳定错误码也不得被状态提示覆盖`() {
        assertEquals(
            "本次任务录音已经失效，请重新说",
            taskFailureMessage(422, "AUDIO_INVALID"),
        )
        assertEquals(
            "没有可靠匹配到联系人，请重新说清联系人称呼",
            taskFailureMessage(422, "NO_CONTACT_MATCH"),
        )
    }
}
