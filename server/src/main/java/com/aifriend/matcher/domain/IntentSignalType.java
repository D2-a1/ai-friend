package com.aifriend.matcher.domain;

/**
 * 关键词匹配产生的意图信号类型。
 *
 * <p>信号只参与候选融合，不能直接触发外发动作。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum IntentSignalType {
    /** 取消当前任务的候选信号，优先级最高。 */
    CANCELLATION,
    /** 明确纠正联系人、动作或内容的候选信号。 */
    CORRECTION,
    /** 微信视频通话候选信号。 */
    VIDEO_CALL,
    /** 微信语音通话候选信号。 */
    VOICE_CALL,
    /** 发送原声和转写文字消息的候选信号。 */
    SEND_MESSAGE
}
