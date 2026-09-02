package com.aifriend.task.domain;

/**
 * 任务会话状态。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum TaskState {
    /** 已创建。 */
    CREATED,
    /** 正在处理。 */
    PROCESSING,
    /** 等待用户选择候选。 */
    AWAITING_SELECTION,
    /** 等待动作型确认。 */
    AWAITING_CONFIRMATION,
    /** 需要重说消息内容。 */
    NEEDS_CONTENT_REPEAT,
    /** 需要重新发起任务。 */
    NEEDS_RETRY,
    /** 已生成有限微信动作计划，等待客户端上报。 */
    EXECUTING,
    /** 用户明确拒绝。 */
    REJECTED,
    /** 用户明确取消。 */
    CANCELLED,
    /** Debug 体验链已走完确认，但没有调用微信或产生渠道结果。 */
    SIMULATED,
    /** 渠道结果已可靠完成。 */
    COMPLETED,
    /** 渠道仅部分完成，不得自动重发。 */
    PARTIAL,
    /** 渠道执行失败。 */
    FAILED;

    /**
     * 判断状态是否不可再被迟到响应推进。
     *
     * @return 终态返回 true
     */
    public boolean terminal() {
        return this == REJECTED || this == CANCELLED || this == SIMULATED || this == COMPLETED
                || this == PARTIAL || this == FAILED;
    }
}
