package com.aifriend.retrieval.domain;

import java.util.Objects;

/**
 * 已按输入顺序排列的向量批次；内部数据只以防御性副本暴露。
 * @author codex
 * @since 1.0.0
 */
public final class EmbeddingBatch {
    private final EmbeddingProfile profile;
    private final float[][] vectors;

    /**
     * 检查全部向量后才接纳整批；不丢弃坏行形成部分成功。
     * @param profile 固定向量空间
     * @param vectors 按输入顺序排列的非零有限向量
     */
    public EmbeddingBatch(EmbeddingProfile profile, float[][] vectors) {
        this.profile = Objects.requireNonNull(profile, "profile");
        if (vectors == null || vectors.length < 1 || vectors.length > 64) {
            throw new IllegalArgumentException("INVALID_EMBEDDING_BATCH");
        }
        this.vectors = new float[vectors.length][];
        for (int row = 0; row < vectors.length; row++) {
            if (vectors[row] == null || vectors[row].length != profile.dimension()) {
                throw new IllegalArgumentException("INVALID_EMBEDDING_DIMENSION");
            }
            float[] copy = vectors[row].clone();
            double normSquared = 0;
            for (float value : copy) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("INVALID_EMBEDDING_VALUE");
                }
                normSquared += (double) value * value;
            }
            if (normSquared == 0 || !Double.isFinite(normSquared)) {
                throw new IllegalArgumentException("INVALID_EMBEDDING_NORM");
            }
            this.vectors[row] = copy;
        }
    }

    /**
     * 读取整批向量共享的空间标识。
     * @return 向量空间标识
     */
    public EmbeddingProfile profile() { return profile; }

    /**
     * 读取当前批次的向量数量。
     * @return 行数
     */
    public int size() { return vectors.length; }

    /**
     * 返回单行副本，不暴露可变内部数组。
     * @param index 输入行索引
     * @return 防御性副本
     */
    public float[] vector(int index) { return vectors[index].clone(); }

    /** 向量不能作为默认日志内容。 */
    @Override public String toString() { return "EmbeddingBatch[size=" + size() + ", vectors=redacted]"; }
}
