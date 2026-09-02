package com.aifriend.task.domain;

/**
 * 当前基础通信链允许的任务意图。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum TaskIntent {
    /** 发送原声与转写文字。 */
    SEND_MESSAGE,
    /** 发起微信语音通话。 */
    VOICE_CALL,
    /** 发起微信视频通话。 */
    VIDEO_CALL,
    /** 取消信号，优先级最高。 */
    CANCEL,
    /** 明确纠正信号。 */
    CORRECT,
    /** 帮助信号。 */
    HELP
}
