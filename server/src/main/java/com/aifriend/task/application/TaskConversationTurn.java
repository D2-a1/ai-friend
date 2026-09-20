package com.aifriend.task.application;

/**
 * 任务短会话的一轮加密上下文。
 *
 * @param sequence 从 1 开始的会话序号
 * @param type 轮次类型
 * @param text 用户转写或系统复述，不得写日志
 */
public record TaskConversationTurn(
        long sequence,
        TaskConversationTurnType type,
        String text) {
}