package com.aifriend.knowledge.domain;

import java.util.Map;

/**
 * 可重建知识图谱节点。
 *
 * @param nodeType 节点类型
 * @param businessKey 去标识化业务键
 * @param properties 不含消息正文、音频、token、openId 的属性
 * @author Codex
 * @since 1.0.0
 */
public record GraphNode(String nodeType, String businessKey, Map<String, String> properties) {

    /**
     * 创建不可变图谱节点。
     *
     * @throws IllegalArgumentException 当节点类型或业务键为空时抛出
     */
    public GraphNode {
        if (nodeType == null || nodeType.isBlank()) {
            throw new IllegalArgumentException("nodeType must not be blank");
        }
        if (businessKey == null || businessKey.isBlank()) {
            throw new IllegalArgumentException("businessKey must not be blank");
        }
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
