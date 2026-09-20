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
            "服务端当前四类安全指令与本机不兼容；请打开录制安全指令页面核对保存结果，不要重复发起联系任务",
            taskFailureMessage(409, "TEMPLATE_INCOMPATIBLE"),
        )
        assertEquals(
            "当前任务与服务器状态冲突，请返回首页后重新开始",
            taskFailureMessage(409, "SESSION_CONFLICT"),
        )
        assertEquals(
            "联系人称呼模板与当前版本不兼容；请到联系人管理删除标为不可用的称呼并重新录制，安全指令无需重录",
            taskFailureMessage(409, "CONTACT_ALIAS_INCOMPATIBLE"),
        )
    }

    @Test
    fun `旧服务没有错误码时仍按 HTTP 状态安全回落`() {
        assertEquals(
            "当前任务与服务器状态已经变化，请返回后重新开始",
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
        assertEquals(
            "任务已经识别，但没有通过微信执行安全检查；没有操作微信",
            taskFailureMessage(422, "ACTION_UNSUPPORTED"),
        )
        assertEquals(
            "任务请求没有通过服务器参数校验，请停止重试并核对应用与服务器版本",
            taskFailureMessage(400, "VALIDATION_FAILED"),
        )
        assertEquals(
            "当前语音识别服务不可用，请稍后再试",
            taskFailureMessage(503, "ASR_UNAVAILABLE"),
        )
        assertEquals(
            "已听到录音，但语义理解服务暂时不可用，请稍后再试",
            taskFailureMessage(503, "SEMANTIC_MODEL_UNAVAILABLE"),
        )
        assertEquals(
            "已听到录音，但语义理解结果异常，本次不会执行，请稍后再试",
            taskFailureMessage(502, "SEMANTIC_MODEL_PROTOCOL_INVALID"),
        )
        assertEquals(
            "操作太频繁，请稍后再试",
            taskFailureMessage(429, "RATE_LIMITED"),
        )
    }

    @Test
    fun `旧服务常见状态也必须提供可执行提示`() {
        assertEquals(
            "任务请求没有通过服务器参数校验，请停止重试并核对应用与服务器版本",
            taskFailureMessage(400, null),
        )
        assertEquals(
            "登录状态已经失效，请重新登录",
            taskFailureMessage(401, null),
        )
        assertEquals(
            "操作太频繁，请稍后再试",
            taskFailureMessage(429, null),
        )
        assertEquals(
            "服务器处理任务时发生内部错误，请稍后再试",
            taskFailureMessage(500, null),
        )
    }
}
