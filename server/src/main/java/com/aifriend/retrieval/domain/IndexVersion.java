package com.aifriend.retrieval.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 索引世代及不可混合的算法/profile 版本。
 * @param generation 世代
 * @param corpusRevision 来源集合修订号
 * @param embeddingProfile 无向量时显式 empty
 * @param tokenizerVersion 分词版本
 * @param chunkerVersion 分块版本
 * @author codex
 * @since 1.0.0
 */
public record IndexVersion(UUID generation, long corpusRevision, Optional<EmbeddingProfile> embeddingProfile,
        String tokenizerVersion, String chunkerVersion) {
    /** 保证版本完整，不用 null 代表未校验。 */
    public IndexVersion {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(embeddingProfile, "embeddingProfile");
        if (corpusRevision < 0 || tokenizerVersion == null || chunkerVersion == null
                || !tokenizerVersion.matches("[A-Za-z0-9._-]{1,100}")
                || !chunkerVersion.matches("[A-Za-z0-9._-]{1,100}")) {
            throw new IllegalArgumentException("INVALID_INDEX_VERSION");
        }
    }
}
