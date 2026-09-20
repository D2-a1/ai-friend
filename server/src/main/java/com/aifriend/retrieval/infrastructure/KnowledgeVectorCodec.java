package com.aifriend.retrieval.infrastructure;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

import com.aifriend.retrieval.domain.EmbeddingProfile;

/**
 * 持久向量的固定大端 float32 编码及绑定来源的完整性检查。
 * 摘要用于检测损坏和错配，不代替数据库授权或数字签名。
 * @author codex
 * @since 1.0.0
 */
public final class KnowledgeVectorCodec {
    /** 创建无状态向量编解码器。 */
    public KnowledgeVectorCodec() { }
    /**
     * 编码时保留原有限向量，检索时再做L2归一化。
     * @param profile 向量空间
     * @param generation 世代
     * @param chunk 片段
     * @param vector 有限非零向量
     * @return 独立字节副本
     */
    public Stored encode(EmbeddingProfile profile, UUID generation, UUID chunk, float[] vector) {
        Objects.requireNonNull(profile, "profile");
        if (vector == null || vector.length != profile.dimension()) {
            throw new IllegalArgumentException("INVALID_VECTOR");
        }
        float[] copy = vector.clone();
        var buffer = ByteBuffer.allocate(copy.length * Float.BYTES).order(ByteOrder.BIG_ENDIAN);
        double norm = 0;
        for (float value : copy) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("INVALID_VECTOR");
            }
            norm += (double) value * value;
            buffer.putFloat(value);
        }
        if (norm == 0 || !Double.isFinite(norm)) {
            throw new IllegalArgumentException("INVALID_VECTOR");
        }
        byte[] bytes = buffer.array();
        return new Stored(bytes, checksum(profile, generation, chunk, bytes));
    }

    /**
     * 拒绝错误长度、来源、profile、摘要以及非法数值，不跳过损坏条目。
     * @param profile 预期向量空间
     * @param generation 预期世代
     * @param chunk 预期片段
     * @param stored 数据库存储材料
     * @return 新建向量数组
     */
    public float[] decode(EmbeddingProfile profile, UUID generation, UUID chunk, Stored stored) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(stored, "stored");
        byte[] bytes = stored.bytes();
        if (bytes.length != profile.dimension() * Float.BYTES
                || !MessageDigest.isEqual(stored.digest(), checksum(profile, generation, chunk, bytes))) {
            throw new IllegalArgumentException("INVALID_VECTOR_STORAGE");
        }
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        float[] result = new float[profile.dimension()];
        for (int i = 0; i < result.length; i++) {
            result[i] = buffer.getFloat();
        }
        // 即使摘要一致也校验数值；摘要不能证明原始写入者遵守向量协议。
        encode(profile, generation, chunk, result);
        return result;
    }

    private byte[] checksum(EmbeddingProfile profile, UUID generation, UUID chunk, byte[] bytes) {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(chunk, "chunk");
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(("knowledge-vector-v1\n" + profile.id() + "\n" + profile.dimension()
                    + "\n" + generation + "\n" + chunk + "\n").getBytes(StandardCharsets.US_ASCII));
            return digest.digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA256_UNAVAILABLE");
        }
    }

    /**
     * 有界存储材料；构造和访问都复制数组。
     * @param bytes 大端float32字节
     * @param digest 绑定元数据的SHA-256
     */
    public record Stored(byte[] bytes, byte[] digest) {
        /** 防止JDBC缓冲或调用方后续修改已校验材料。 */
        public Stored {
            if (bytes == null || bytes.length < 4 || bytes.length > 4096 * Float.BYTES
                    || bytes.length % Float.BYTES != 0 || digest == null || digest.length != 32) {
                throw new IllegalArgumentException("INVALID_VECTOR_STORAGE");
            }
            bytes = bytes.clone();
            digest = digest.clone();
        }
        /**
         * 读取独立的编码缓冲。
         * @return 大端float32字节副本
         */
        @Override public byte[] bytes() { return bytes.clone(); }
        /**
         * 读取独立的元数据绑定摘要。
         * @return 32字节SHA-256副本
         */
        @Override public byte[] digest() { return digest.clone(); }
        @Override public String toString() { return "Stored[redacted]"; }
    }
}
