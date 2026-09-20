package com.aifriend.retrieval.application;

/**
 * 固定检索错误，不携带来源正文或数据库异常细节。
 * @author codex
 * @since 1.0.0
 */
public final class KnowledgeRetrievalException extends RuntimeException {
    /** 固定失败类别。 */
    public enum Kind {
        /** 尚无有效索引。 */ NO_INDEX,
        /** 索引不完整或损坏。 */ INDEX_INVALID,
        /** 返回前来源已失效。 */ SOURCE_CHANGED
    }
    /** 可序列化的固定失败类别，不含敏感原文。 */
    private final Kind kind;

    /**
     * 创建脱敏检索故障。
     * @param kind 固定类别
     */
    public KnowledgeRetrievalException(Kind kind) {
        super(java.util.Objects.requireNonNull(kind, "kind").name());
        this.kind = kind;
    }

    /**
     * 读取检索索引或来源的失败类别。
     * @return 固定类别
     */
    public Kind kind() { return kind; }
}
