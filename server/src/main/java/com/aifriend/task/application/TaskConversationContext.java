package com.aifriend.task.application;

import java.util.ArrayList;
import java.util.List;

/**
 * 单次唤醒任务内的短期记忆。
 *
 * <p>只保留最近若干轮，用于“不对，是视频通话”这类基于当前草稿的修订。
 * 它不是长期个人记忆，也不构成任何微信动作授权。
 *
 * @param turnNumber 已接收的用户轮次数
 * @param turns 最近的加密会话轮次
 */
public record TaskConversationContext(
        long turnNumber,
        List<TaskConversationTurn> turns) {

    private static final int MAX_TURNS = 8;

    /**
     * 创建空短期上下文。
     *
     * @return 空上下文
     */
    public static TaskConversationContext empty() {
        return new TaskConversationContext(0, List.of());
    }

    /**
     * 创建首轮请求及可空系统复述。
     *
     * @param transcript 首轮用户转写
     * @param rehearsal 可空的系统完整复述
     * @return 首轮短期上下文
     */
    public static TaskConversationContext initial(String transcript, String rehearsal) {
        return empty().appendUser(TaskConversationTurnType.USER_REQUEST, transcript)
                .appendSystemRehearsal(rehearsal);
    }

    /**
     * 追加一轮用户输入。
     *
     * @param type 用户轮次类型
     * @param text 本轮临时转写
     * @return 截断到最近八轮的新上下文
     */
    public TaskConversationContext appendUser(
            TaskConversationTurnType type,
            String text) {
        if (type != TaskConversationTurnType.USER_REQUEST
                && type != TaskConversationTurnType.USER_RETRY
                && type != TaskConversationTurnType.USER_CORRECTION) {
            throw new IllegalArgumentException("非用户会话轮次");
        }
        return append(turnNumber + 1, type, text, turnNumber + 1);
    }

    /**
     * 追加当前草稿的系统复述。
     *
     * @param text 当前草稿完整复述，可空
     * @return 截断到最近八轮的新上下文
     */
    public TaskConversationContext appendSystemRehearsal(String text) {
        if (text == null || text.isBlank()) {
            return this;
        }
        return append(turnNumber, TaskConversationTurnType.SYSTEM_REHEARSAL,
                text, turnNumber);
    }

    private TaskConversationContext append(
            long sequence,
            TaskConversationTurnType type,
            String text,
            long nextTurnNumber) {
        if (text == null || text.isBlank()) {
            return this;
        }
        List<TaskConversationTurn> updated = new ArrayList<>(turns);
        updated.add(new TaskConversationTurn(sequence, type, text.strip()));
        int from = Math.max(0, updated.size() - MAX_TURNS);
        return new TaskConversationContext(
                nextTurnNumber, List.copyOf(updated.subList(from, updated.size())));
    }

    /** 兼容旧载荷并固化列表。 */
    public TaskConversationContext {
        if (turnNumber < 0) {
            turnNumber = 0;
        }
        List<TaskConversationTurn> normalized =
                turns == null ? List.of() : List.copyOf(turns);
        int from = Math.max(0, normalized.size() - MAX_TURNS);
        turns = List.copyOf(normalized.subList(from, normalized.size()));
    }
}