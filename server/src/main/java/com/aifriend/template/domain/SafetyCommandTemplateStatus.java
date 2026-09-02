package com.aifriend.template.domain;

/**
 * 安全指令模板生命周期状态。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum SafetyCommandTemplateStatus {
    /** 当前唯一有效版本。 */
    ACTIVE,
    /** 已被新的四类整批模板替换且已清空密文。 */
    REPLACED
}
