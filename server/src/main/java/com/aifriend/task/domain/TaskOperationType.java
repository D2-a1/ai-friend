package com.aifriend.task.domain;

/**
 * 任务会话内受幂等保护的写操作类型。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum TaskOperationType {
    /** 候选选择。 */
    SELECTION,
    /** 同一语音会话内的需求重说或纠错。 */
    REVISION,
    /** 动作型确认。 */
    CONFIRMATION,
    /** 微信渠道结果上报。 */
    CHANNEL_RESULT
}
