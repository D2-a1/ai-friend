package com.aifriend.retrieval.domain;

/**
 * 经批准的完整向量空间标识；同维度不能代替 profile 相等。
 * @param id 绑定模型与预处理配置的不可变版本标识
 * @param dimension 维度
 * @author codex
 * @since 1.0.0
 */
public record EmbeddingProfile(String id, int dimension) {
    /** 校验配置标识和维度硬上限。 */
    public EmbeddingProfile {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,100}") || dimension < 1 || dimension > 4096) {
            throw new IllegalArgumentException("INVALID_EMBEDDING_PROFILE");
        }
    }
}
