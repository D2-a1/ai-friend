package com.aifriend.template.application;

import com.aifriend.task.domain.TaskIntent;

/**
 * 事务外日常指令声学意图分类结果。
 *
 * @param band 声学分档
 * @param intent 唯一分档对应意图；其他分档为空
 * @author Codex
 * @since 1.0.0
 */
public record RoutineCommandRuntimeMatch(
        RoutineCommandRuntimeMatchBand band,
        TaskIntent intent) {

    /** 校验分档和意图组合。 */
    public RoutineCommandRuntimeMatch {
        if (band == null
                || (band == RoutineCommandRuntimeMatchBand.UNIQUE && intent == null)
                || (band != RoutineCommandRuntimeMatchBand.UNIQUE && intent != null)) {
            throw new IllegalArgumentException("日常指令声学分档无效");
        }
    }

    /**
     * 创建无匹配结果。
     *
     * @return 无匹配结果
     */
    public static RoutineCommandRuntimeMatch none() {
        return new RoutineCommandRuntimeMatch(
                RoutineCommandRuntimeMatchBand.NONE, null);
    }

    /**
     * 创建唯一意图结果。
     *
     * @param intent 唯一声学意图
     * @return 唯一意图结果
     */
    public static RoutineCommandRuntimeMatch unique(TaskIntent intent) {
        return new RoutineCommandRuntimeMatch(
                RoutineCommandRuntimeMatchBand.UNIQUE, intent);
    }

    /**
     * 创建临界或跨意图不唯一结果。
     *
     * @return 临界结果
     */
    public static RoutineCommandRuntimeMatch ambiguous() {
        return new RoutineCommandRuntimeMatch(
                RoutineCommandRuntimeMatchBand.AMBIGUOUS, null);
    }
}
