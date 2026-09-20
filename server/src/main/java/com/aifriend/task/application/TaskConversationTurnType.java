package com.aifriend.task.application;

/**
 * 单次唤醒会话内的轮次类型。
 *
 * <p>这些内容只保存在任务加密载荷中，跟随任务 TTL 清理。
 */
public enum TaskConversationTurnType {
    /** 用户首次说出需求。 */
    USER_REQUEST,
    /** 用户在当前会话中完整重说。 */
    USER_RETRY,
    /** 用户对已复述草稿做定向纠正。 */
    USER_CORRECTION,
    /** 系统根据当前结构化草稿生成的复述。 */
    SYSTEM_REHEARSAL
}