package com.aifriend.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.aifriend.agent.domain.AgentCapabilityId;
import com.aifriend.agent.domain.CapabilityStage;

/**
 * 默认能力注册表安全状态测试。
 *
 * @author Codex
 * @since 1.0.0
 */
class DefaultCapabilityRegistryTest {

    private final DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry();

    @Test
    void semanticModelMustRemainHiddenAndInDevelopmentBeforeProviderEvaluation() {
        var descriptor = registry.get(AgentCapabilityId.SEMANTIC_MATCHING);

        assertThat(descriptor.stage()).isEqualTo(CapabilityStage.IN_DEVELOPMENT);
        assertThat(descriptor.externallyExposed()).isFalse();
        assertThat(descriptor.isAvailable()).isFalse();
    }

    @Test
    void personalMemoryManagementMustRemainHiddenAndInDevelopment() {
        var descriptor = registry.get(AgentCapabilityId.PERSONAL_MEMORY);

        assertThat(descriptor.stage()).isEqualTo(CapabilityStage.IN_DEVELOPMENT);
        assertThat(descriptor.externallyExposed()).isFalse();
        assertThat(descriptor.isAvailable()).isFalse();
    }

    @Test
    void basicCapabilitiesMustNotBeReportedAvailableBeforeImplementation() {
        var descriptor = registry.get(AgentCapabilityId.COMMUNICATION);

        assertThat(descriptor.stage()).isEqualTo(CapabilityStage.IN_DEVELOPMENT);
        assertThat(descriptor.isAvailable()).isFalse();
    }
}
