package com.aifriend.retrieval.domain;

import java.util.Objects;

/**
 * 有来源的排序证据；分数不是概率，亦不证明答案正确。
 * @param chunk 原文片段
 * @param score 有限非负排序分数
 * @author codex
 * @since 1.0.0
 */
public record RetrievalEvidence(KnowledgeChunk chunk, double score) {
    /** 拒绝损坏或非有限排序结果。 */
    public RetrievalEvidence {
        Objects.requireNonNull(chunk, "chunk");
        if (!Double.isFinite(score) || score < 0) {
            throw new IllegalArgumentException("INVALID_RETRIEVAL_SCORE");
        }
    }
}
