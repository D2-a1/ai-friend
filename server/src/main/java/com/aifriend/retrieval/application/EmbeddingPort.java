package com.aifriend.retrieval.application;

import java.time.Duration;
import java.util.List;

import com.aifriend.retrieval.domain.EmbeddingBatch;
import com.aifriend.retrieval.domain.EmbeddingProfile;

/**
 * 公开知识专用向量端口，不能接收私人图谱。
 * @author codex
 * @since 1.0.0
 */
public interface EmbeddingPort {
    /**
     * 一次有界调用；调用方须先持久预留配额及核对适用同意。
     * @param profile 固定的经批准向量空间
     * @param texts 最多64条且每条最多600码点的公开文本
     * @param remainingBudget 本次调用剩余预算，不得重置逻辑问题总期限
     * @return 与输入数量、顺序和profile完全一致的向量
     */
    EmbeddingBatch embed(EmbeddingProfile profile, List<String> texts, Duration remainingBudget);
}
