package com.aifriend.template.domain;

/**
 * 四类强制动作型方言安全指令。
 *
 * <p>本枚举只标识发音内容类别，不用于说话人身份判断。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum SafetyCommandType {
    /** 确认发送当前消息。 */
    CONFIRM_SEND,
    /** 确认发起当前语音或视频通话。 */
    CONFIRM_CALL,
    /** 取消当前任务。 */
    CANCEL,
    /** 否定当前理解并重新说。 */
    REJECT_RETRY
}
