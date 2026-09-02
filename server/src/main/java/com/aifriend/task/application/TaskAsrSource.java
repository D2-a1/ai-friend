package com.aifriend.task.application;

/**
 * 任务转写候选的本地识别来源。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum TaskAsrSource {
    /** 已验签方言包指定的主方言模型。 */
    PRIMARY,
    /** 只提供辅助证据的普通话模型。 */
    MANDARIN_ASSIST
}
