package com.aifriend.task.application;

/**
 * 日常指令声学模板对当前有限意图的复核状态。
 *
 * <p>模板只能作为本地复核信号，不能单独生成联系人、确认或微信动作授权。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum TaskRoutineCommandMatchStatus {

    /** 当前任务没有可参与复核的动作范围或兼容模板。 */
    NOT_APPLICABLE,

    /** 已执行声学比较，但没有模板进入签名候选阈值。 */
    NO_MATCH,

    /** 唯一声学意图与转写有限意图一致。 */
    CORROBORATED,

    /** 声学意图临界、跨意图不唯一或与转写意图冲突。 */
    CONFLICT
}
