package com.aifriend.knowledge.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 同owner、同世代的有限关系；端点一致性由完整快照校验。
 * @param ownerUserId 所属用户
 * @param generation 快照世代
 * @param id 边 ID
 * @param fromId 起点
 * @param toId 终点
 * @param type 有限关系
 * @author codex
 * @since 1.0.0
 */
public record GraphEdge(UUID ownerUserId, UUID generation, UUID id, UUID fromId, UUID toId, Type type) {
    /** 首期不推断家族血缘或任意业务关系。 */
    public enum Type {
        /** 用户拥有已绑定亲友。 */ HAS_CONTACT,
        /** 已绑定亲友拥有有效称呼。 */ HAS_ALIAS
    }

    /** 拒绝空引用和自环。 */
    public GraphEdge {
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fromId, "fromId");
        Objects.requireNonNull(toId, "toId");
        Objects.requireNonNull(type, "type");
        if (fromId.equals(toId)) {
            throw new IllegalArgumentException("GRAPH_CYCLE");
        }
    }

    /** 防止身份引用进入默认日志。 */
    @Override public String toString() { return "GraphEdge[type=" + type + ", references=redacted]"; }
}
