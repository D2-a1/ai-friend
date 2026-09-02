package com.aifriend.task.application;

import com.aifriend.task.domain.TaskIntent;

/**
 * 日常指令声学模板对当前任务动作的最小复核结果。
 *
 * @param status 有限复核状态
 * @param matchedIntent 唯一命中的声学意图；其他状态为空
 * @author Codex
 * @since 1.0.0
 */
public record TaskRoutineCommandMatchDecision(
        TaskRoutineCommandMatchStatus status,
        TaskIntent matchedIntent) {

    /** 校验状态与唯一意图组合。 */
    public TaskRoutineCommandMatchDecision {
        if (status == null
                || (status == TaskRoutineCommandMatchStatus.CORROBORATED
                        && matchedIntent == null)
                || (status != TaskRoutineCommandMatchStatus.CORROBORATED
                        && matchedIntent != null)) {
            throw new IllegalArgumentException("日常指令复核结果无效");
        }
    }

    /**
     * 判断当前任务是否必须保守要求重说。
     *
     * @return 只有声学冲突返回 {@code true}
     */
    public boolean requiresRetry() {
        return status == TaskRoutineCommandMatchStatus.CONFLICT;
    }

    /**
     * 创建不适用结果。
     *
     * @return 不适用结果
     */
    public static TaskRoutineCommandMatchDecision notApplicable() {
        return new TaskRoutineCommandMatchDecision(
                TaskRoutineCommandMatchStatus.NOT_APPLICABLE, null);
    }

    /**
     * 创建无匹配结果。
     *
     * @return 无匹配结果
     */
    public static TaskRoutineCommandMatchDecision noMatch() {
        return new TaskRoutineCommandMatchDecision(
                TaskRoutineCommandMatchStatus.NO_MATCH, null);
    }

    /**
     * 创建已复核结果。
     *
     * @param intent 与转写结论一致的唯一声学意图
     * @return 已复核结果
     */
    public static TaskRoutineCommandMatchDecision corroborated(TaskIntent intent) {
        return new TaskRoutineCommandMatchDecision(
                TaskRoutineCommandMatchStatus.CORROBORATED, intent);
    }

    /**
     * 创建冲突结果。
     *
     * @return 必须重说的冲突结果
     */
    public static TaskRoutineCommandMatchDecision conflict() {
        return new TaskRoutineCommandMatchDecision(
                TaskRoutineCommandMatchStatus.CONFLICT, null);
    }
}
