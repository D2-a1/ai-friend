package com.aifriend.core.agent

/**
 * 当前 Agent 允许生成的有限动作。
 *
 * 模型和检索结果只能产生候选，最终动作还必须通过本地安全策略与动作型确认。
 */
enum class AgentAction {
    ASK_CONTACT,
    ASK_ACTION,
    ASK_CONTENT,
    REPLAY_AND_CONFIRM,
    SEND_MESSAGE,
    START_VOICE_CALL,
    START_VIDEO_CALL,
    CANCEL,
    SLEEP,
}
