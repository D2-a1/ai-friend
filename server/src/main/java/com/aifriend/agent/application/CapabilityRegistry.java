package com.aifriend.agent.application;

import java.util.List;

import com.aifriend.agent.domain.AgentCapabilityId;
import com.aifriend.agent.domain.CapabilityDescriptor;

/**
 * Agent 能力注册表端口。
 *
 * <p>注册表只描述交付状态，不负责执行能力，也不能把模型结果转换为执行授权。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface CapabilityRegistry {

    /**
     * 查询指定能力。
     *
     * @param capabilityId 能力标识
     * @return 能力描述
     */
    CapabilityDescriptor get(AgentCapabilityId capabilityId);

    /**
     * 列出全部已注册能力。
     *
     * @return 不可变能力列表
     */
    List<CapabilityDescriptor> list();
}
