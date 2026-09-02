package com.aifriend.knowledge.domain;

import java.util.Map;

/**
 * 可重建知识图谱关系。
 *
 * @param relationType 关系类型
 * @param fromBusinessKey 起点业务键
 * @param toBusinessKey 终点业务键
 * @param properties 不含敏感正文的关系属性
 * @author Codex
 * @since 1.0.0
 */
public record GraphEdge(
        String relationType,
        String fromBusinessKey,
        String toBusinessKey,
        Map<String, String> properties) {

    /**
     * 创建不可变图谱关系。
     *
     * @throws IllegalArgumentException 当关系类型或任一端点业务键为空时抛出
     */
    public GraphEdge {
        if (relationType == null || relationType.isBlank()) {
            throw new IllegalArgumentException("relationType must not be blank");
        }
        if (fromBusinessKey == null || fromBusinessKey.isBlank()) {
            throw new IllegalArgumentException("fromBusinessKey must not be blank");
        }
        if (toBusinessKey == null || toBusinessKey.isBlank()) {
            throw new IllegalArgumentException("toBusinessKey must not be blank");
        }
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
