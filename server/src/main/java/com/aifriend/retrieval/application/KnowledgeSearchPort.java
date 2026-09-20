package com.aifriend.retrieval.application;

import java.util.List;

import com.aifriend.retrieval.domain.EmbeddingProfile;
import com.aifriend.retrieval.domain.KnowledgeChunk;
import com.aifriend.retrieval.domain.RetrievalEvidence;
import com.aifriend.retrieval.domain.RetrievalQuery;

/**
 * 已验证公开语料的纯检索边界，应用层不依赖算法/存储适配器。
 * @author codex
 * @since 1.0.0
 */
public interface KnowledgeSearchPort {
    /**
     * 词法召回不依赖Embedding可用性。
     * @param snapshot 完整快照
     * @param query 有界问题及适用版本
     * @param topK 1至20
     * @return 正分排序候选
     */
    List<RetrievalEvidence> keyword(KnowledgeRepositoryPort.Snapshot snapshot, RetrievalQuery query, int topK);

    /**
     * 仅在索引profile与查询向量一致时进行余弦召回。
     * @param snapshot 完整快照
     * @param query 有界问题及适用版本
     * @param profile 查询向量空间
     * @param queryVector 查询向量
     * @param topK 1至20
     * @return 带原始余弦分数的候选
     */
    List<VectorHit> vector(KnowledgeRepositoryPort.Snapshot snapshot, RetrievalQuery query,
            EmbeddingProfile profile, float[] queryVector, int topK);

    /**
     * 余弦结果允许负值，不伪装成概率。
     * @param chunk 原文来源
     * @param similarity [-1,1]的余弦
     */
    record VectorHit(KnowledgeChunk chunk, double similarity) {
        /** 拒绝损坏数值。 */
        public VectorHit {
            java.util.Objects.requireNonNull(chunk, "chunk");
            if (!Double.isFinite(similarity) || similarity < -1 || similarity > 1) {
                throw new IllegalArgumentException("INVALID_COSINE");
            }
        }
    }
}
