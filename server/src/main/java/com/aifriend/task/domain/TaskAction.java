package com.aifriend.task.domain;

/**
 * 服务端当前允许客户端提交的任务动作。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum TaskAction {
    /** 选择候选联系人。 */
    SELECT_CANDIDATE,
    /** 使用个人安全指令确认发送。 */
    CONFIRM_SEND,
    /** 使用个人安全指令确认通话。 */
    CONFIRM_CALL,
    /** 明确拒绝本次任务。 */
    REJECT,
    /** 明确取消本次任务。 */
    CANCEL,
    /** 重新发起任务。 */
    RETRY
}
