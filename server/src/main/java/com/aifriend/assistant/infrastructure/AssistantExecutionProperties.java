package com.aifriend.assistant.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 在线问答专用资源；与知识导入隔离，不引入虚拟线程或无界队列。
 * 运行时装配另行提供，单独声明不自动启动线程。
 * @param workers 1至16个工作线程
 * @param queueCapacity 0至128；0不排队
 * @author Codex
 * @since 1.0.0
 */
@ConfigurationProperties(prefix="ai-friend.knowledge.request")
public record AssistantExecutionProperties(@DefaultValue("2") int workers,@DefaultValue("16") int queueCapacity) {
    /** 所有资源必须有界。 */
    public AssistantExecutionProperties {
        if(workers<1 || workers>16 || queueCapacity<0 || queueCapacity>128)
            throw new IllegalArgumentException("INVALID_ASSISTANT_EXECUTOR_CONFIG");
    }
}
