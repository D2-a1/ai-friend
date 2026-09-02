package com.aifriend.agent.domain;

/**
 * Agent 能力交付阶段。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum CapabilityStage {
    /** 正在开发，未达到对外可用标准。 */
    IN_DEVELOPMENT,
    /** 已完成验收并允许使用。 */
    AVAILABLE,
    /** 仅保留架构扩展位且显式禁用。 */
    RESERVED_DISABLED
}
