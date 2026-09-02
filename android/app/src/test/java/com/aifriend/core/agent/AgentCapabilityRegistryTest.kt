package com.aifriend.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Agent 能力注册表安全默认值测试。
 */
class AgentCapabilityRegistryTest {
    private val registry = AgentCapabilityRegistry.safeDefault()

    @Test
    fun futureCapabilityMustRemainDisabledAndHidden() {
        val descriptor = registry.get(AgentCapabilityId.SEMANTIC_MATCHING)

        assertEquals(CapabilityStage.RESERVED_DISABLED, descriptor.stage)
        assertFalse(descriptor.externallyExposed)
        assertFalse(descriptor.isAvailable)
    }

    @Test
    fun communicationMustNotBeReportedAvailableBeforeImplementation() {
        val descriptor = registry.get(AgentCapabilityId.COMMUNICATION)

        assertEquals(CapabilityStage.IN_DEVELOPMENT, descriptor.stage)
        assertFalse(descriptor.isAvailable)
    }
}
