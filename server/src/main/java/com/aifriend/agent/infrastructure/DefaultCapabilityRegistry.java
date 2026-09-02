package com.aifriend.agent.infrastructure;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aifriend.agent.application.CapabilityRegistry;
import com.aifriend.agent.domain.AgentCapabilityId;
import com.aifriend.agent.domain.CapabilityDescriptor;
import com.aifriend.agent.domain.CapabilityStage;

/**
 * 默认 Agent 能力注册表。
 *
 * <p>基础通信能力标记为开发中；问答、提醒、外部知识、记忆、图谱、向量检索和云模型
 * 仅保留扩展位，默认关闭且不允许通过公开 API 或 UI 暴露。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class DefaultCapabilityRegistry implements CapabilityRegistry {

    private final Map<AgentCapabilityId, CapabilityDescriptor> descriptors;

    /**
     * 使用安全默认值创建注册表。
     */
    public DefaultCapabilityRegistry() {
        EnumMap<AgentCapabilityId, CapabilityDescriptor> registry =
                new EnumMap<>(AgentCapabilityId.class);
        registerBasic(registry, AgentCapabilityId.COMMUNICATION);
        registerBasic(registry, AgentCapabilityId.CANCELLATION);
        registerBasic(registry, AgentCapabilityId.CORRECTION);
        registerBasic(registry, AgentCapabilityId.CONFIRMATION);
        registerReserved(registry, AgentCapabilityId.SEMANTIC_MATCHING);
        registerReserved(registry, AgentCapabilityId.QUESTION_ANSWERING);
        registerReserved(registry, AgentCapabilityId.REMINDER);
        registerReserved(registry, AgentCapabilityId.EXTERNAL_KNOWLEDGE);
        registerReserved(registry, AgentCapabilityId.PERSONAL_MEMORY);
        registerReserved(registry, AgentCapabilityId.KNOWLEDGE_GRAPH);
        registerReserved(registry, AgentCapabilityId.VECTOR_RETRIEVAL);
        registerReserved(registry, AgentCapabilityId.CLOUD_MODEL);
        descriptors = Map.copyOf(registry);
    }

    /**
     * 按能力标识查询能力描述。
     *
     * @param capabilityId 能力标识
     * @return 已注册的能力描述
     * @throws IllegalArgumentException 当能力标识未注册时抛出
     */
    @Override
    public CapabilityDescriptor get(AgentCapabilityId capabilityId) {
        CapabilityDescriptor descriptor = descriptors.get(capabilityId);
        if (descriptor == null) {
            throw new IllegalArgumentException("unregistered capability: " + capabilityId);
        }
        return descriptor;
    }

    /**
     * 查询全部能力描述快照。
     *
     * @return 不可变能力描述列表
     */
    @Override
    public List<CapabilityDescriptor> list() {
        return List.copyOf(descriptors.values());
    }

    private void registerBasic(
            EnumMap<AgentCapabilityId, CapabilityDescriptor> registry,
            AgentCapabilityId capabilityId) {
        registry.put(capabilityId, new CapabilityDescriptor(
                capabilityId,
                CapabilityStage.IN_DEVELOPMENT,
                false,
                "基础能力按当前开发计划实现，完成验收前不对外宣称可用"));
    }

    private void registerReserved(
            EnumMap<AgentCapabilityId, CapabilityDescriptor> registry,
            AgentCapabilityId capabilityId) {
        registry.put(capabilityId, new CapabilityDescriptor(
                capabilityId,
                CapabilityStage.RESERVED_DISABLED,
                false,
                "未来能力仅保留扩展边界，未进入当前实现范围"));
    }
}
