package com.aifriend.retrieval.application;

import java.util.List;

/**
 * 关键词、向量、图谱和记忆混合检索端口。
 *
 * <p>当前仅保留稳定边界，不提供默认实现，不允许触发外部知识调用。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface HybridRetrievalPort {

    /**
     * 检索去标识化知识候选。
     *
     * @param query 不含消息正文和敏感身份数据的查询
     * @param limit 最大候选数量
     * @return 候选标识列表
     */
    List<String> retrieve(String query, int limit);
}
