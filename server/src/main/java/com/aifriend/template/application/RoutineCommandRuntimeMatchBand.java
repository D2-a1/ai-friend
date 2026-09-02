package com.aifriend.template.application;

/**
 * 日常指令运行时声学意图分档。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum RoutineCommandRuntimeMatchBand {

    /** 没有意图进入签名候选阈值。 */
    NONE,

    /** 唯一意图满足签名唯一阈值和跨意图差距。 */
    UNIQUE,

    /** 存在候选但未满足唯一阈值或跨意图差距。 */
    AMBIGUOUS
}
