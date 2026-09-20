package com.aifriend.knowledge.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 私人关系投影只保留权威引用，不接受称呼/定位/音频属性。
 * @param ownerUserId 所属用户
 * @param generation 同一快照世代
 * @param id 节点 ID
 * @param type 有限节点类别
 * @param sourceId 权威来源 ID
 * @param sourceVersion 权威来源版本
 * @author codex
 * @since 1.0.0
 */
public record GraphNode(UUID ownerUserId, UUID generation, UUID id, Type type, UUID sourceId, long sourceVersion) {
    /** 节点类别；不得由模型扩展。 */
    public enum Type {
        /** 当前用户。 */ USER,
        /** 已绑定亲友。 */ CONTACT,
        /** 当前有效称呼的来源引用。 */ ALIAS
    }

    /** 校验所有引用，用户根必须引用当前owner。 */
    public GraphNode {
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sourceId, "sourceId");
        if (sourceVersion < 0 || (type == Type.USER && !sourceId.equals(ownerUserId))) {
            throw new IllegalArgumentException("INVALID_GRAPH_NODE");
        }
    }

    /** 防止身份引用进入默认日志。 */
    @Override public String toString() { return "GraphNode[type=" + type + ", references=redacted]"; }
}
