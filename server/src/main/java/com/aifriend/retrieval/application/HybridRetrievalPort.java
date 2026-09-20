package com.aifriend.retrieval.application;

import com.aifriend.retrieval.domain.RetrievalQuery;
import com.aifriend.retrieval.domain.RetrievalResult;

/**
 * 公开知识关键词和向量混合检索端口；私人图谱另走独立端口。
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
     * @param query 已经完成适用同意校验的有界公开知识查询
     * @return 带来源版本和实际检索模式的证据，不是执行授权
     */
    RetrievalResult retrieve(RetrievalQuery query);
}
