package com.aifriend.agent.domain;

import java.util.Objects;

/**
 * Agent 能力描述。
 *
 * @param id 能力标识
 * @param stage 交付阶段
 * @param externallyExposed 是否允许通过 UI 或公开 API 暴露
 * @param reason 当前阶段说明
 * @author Codex
 * @since 1.0.0
 */
public record CapabilityDescriptor(
        AgentCapabilityId id,
        CapabilityStage stage,
        boolean externallyExposed,
        String reason) {

    /**
     * 创建能力描述并校验占位能力不能意外暴露。
     *
     * @throws NullPointerException 当能力标识或交付阶段为空时抛出
     * @throws IllegalArgumentException 当阶段说明为空或禁用能力被标记为对外暴露时抛出
     */
    public CapabilityDescriptor {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(stage, "stage must not be null");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (stage == CapabilityStage.RESERVED_DISABLED && externallyExposed) {
            throw new IllegalArgumentException("reserved capability must not be exposed");
        }
    }

    /**
     * 判断能力是否已经可调用。
     *
     * @return 仅 AVAILABLE 阶段返回 true
     */
    public boolean isAvailable() {
        return stage == CapabilityStage.AVAILABLE;
    }
}
