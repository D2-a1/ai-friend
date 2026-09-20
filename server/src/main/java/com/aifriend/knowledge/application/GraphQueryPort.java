package com.aifriend.knowledge.application;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.aifriend.knowledge.domain.ContactDisplay;
import com.aifriend.knowledge.domain.GraphQueryType;
import com.aifriend.retrieval.domain.KnowledgeText;

/**
 * 有限私人关系查询，不能执行联系动作或调用外部模型。
 * @author codex
 * @since 1.0.0
 */
public interface GraphQueryPort {
    /**
     * 查询并在返回前重新核对权威来源及同意。
     * @param query 来自已认证owner的有限查询
     * @return 当前候选，歧义不可自动选择
     */
    Result query(Query query);

    /**
     * 固定参数组合，杜绝任意遍历语言。
     * @param ownerUserId 只能来自服务端认证主体
     * @param type 查询类型
     * @param contactId LIST_ALIASES必填，其他类型禁止
     * @param alias FIND_CONTACT_BY_ALIAS必填，其他类型禁止
     */
    record Query(UUID ownerUserId, GraphQueryType type, Optional<UUID> contactId, Optional<String> alias) {
        /** 校验类型和参数组合。 */
        public Query {
            Objects.requireNonNull(ownerUserId, "ownerUserId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(contactId, "contactId");
            Objects.requireNonNull(alias, "alias");
            boolean valid = switch (type) {
                case LIST_CONTACTS -> contactId.isEmpty() && alias.isEmpty();
                case LIST_ALIASES -> contactId.isPresent() && alias.isEmpty();
                case FIND_CONTACT_BY_ALIAS -> contactId.isEmpty() && alias.isPresent();
            };
            if (!valid) {
                throw new IllegalArgumentException("INVALID_GRAPH_QUERY");
            }
            alias.ifPresent(text -> KnowledgeText.require(text, 100, 400, false));
        }

        /** 不记录用户或称呼。 */
        @Override public String toString() { return "GraphQuery[type=" + type + ", private=redacted]"; }
    }

    /**
     * 确定性查询结果，包含最终复验的来源摘要。
     * @param sourceDigest 已复验来源
     * @param candidates 只读候选，不是执行目标
     * @param ambiguous 是否存在需要用户区分的同名候选
     */
    record Result(String sourceDigest, List<ContactDisplay> candidates, boolean ambiguous) {
        /** 禁止重复候选和不真实歧义标记。 */
        public Result {
            if (sourceDigest == null || !sourceDigest.matches("[a-f0-9]{64}")
                    || candidates == null || candidates.size() > 20 || (ambiguous && candidates.size() < 2)) {
                throw new IllegalArgumentException("INVALID_GRAPH_RESULT");
            }
            candidates = List.copyOf(candidates);
            var ids = new HashSet<UUID>();
            for (var candidate : candidates) {
                if (!ids.add(candidate.contactId())) {
                    throw new IllegalArgumentException("DUPLICATE_GRAPH_CANDIDATE");
                }
            }
        }

        /** 不把关系来源及私人内容写入日志。 */
        @Override public String toString() { return "GraphQueryResult[count=" + candidates.size() + "]"; }
    }
}
