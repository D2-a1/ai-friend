package com.aifriend.personalization.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 加密长期偏好及其最小生命周期元数据。
 *
 * @param ownerUserId owner UUID
 * @param preferencesCipher AES-GCM 偏好密文，删除后为空
 * @param preferencesDigest 密文摘要，删除后为空
 * @param policyVersion 保存时适用的政策版本
 * @param status 生命周期状态
 * @param updateIdempotencyKeyHash 最近更新幂等键摘要，可空
 * @param updateRequestHash 最近更新请求摘要，可空
 * @param deleteIdempotencyKeyHash 最近删除幂等键摘要，可空
 * @param deleteRequestHash 最近删除请求摘要，可空
 * @param version 乐观锁版本
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param deletedAt 删除时间，可空
 * @author Codex
 * @since 1.0.0
 */
public record PersonalMemoryRecord(
        UUID ownerUserId,
        byte[] preferencesCipher,
        byte[] preferencesDigest,
        String policyVersion,
        PersonalMemoryStatus status,
        byte[] updateIdempotencyKeyHash,
        byte[] updateRequestHash,
        byte[] deleteIdempotencyKeyHash,
        byte[] deleteRequestHash,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt) {

    /**
     * 防御性复制全部二进制字段。
     */
    public PersonalMemoryRecord {
        preferencesCipher = cloneNullable(preferencesCipher);
        preferencesDigest = cloneNullable(preferencesDigest);
        updateIdempotencyKeyHash = cloneNullable(updateIdempotencyKeyHash);
        updateRequestHash = cloneNullable(updateRequestHash);
        deleteIdempotencyKeyHash = cloneNullable(deleteIdempotencyKeyHash);
        deleteRequestHash = cloneNullable(deleteRequestHash);
    }

    /**
     * 获取偏好密文的副本。
     *
     * @return 偏好密文防御性副本，删除后为空
     */
    @Override
    public byte[] preferencesCipher() { return cloneNullable(preferencesCipher); }

    /**
     * 获取密文摘要的副本。
     *
     * @return 密文摘要防御性副本，删除后为空
     */
    @Override
    public byte[] preferencesDigest() { return cloneNullable(preferencesDigest); }

    /**
     * 获取更新幂等键摘要的副本。
     *
     * @return 更新幂等键摘要防御性副本，可空
     */
    @Override
    public byte[] updateIdempotencyKeyHash() { return cloneNullable(updateIdempotencyKeyHash); }

    /**
     * 获取更新请求摘要的副本。
     *
     * @return 更新请求摘要防御性副本，可空
     */
    @Override
    public byte[] updateRequestHash() { return cloneNullable(updateRequestHash); }

    /**
     * 获取删除幂等键摘要的副本。
     *
     * @return 删除幂等键摘要防御性副本，可空
     */
    @Override
    public byte[] deleteIdempotencyKeyHash() { return cloneNullable(deleteIdempotencyKeyHash); }

    /**
     * 获取删除请求摘要的副本。
     *
     * @return 删除请求摘要防御性副本，可空
     */
    @Override
    public byte[] deleteRequestHash() { return cloneNullable(deleteRequestHash); }

    private static byte[] cloneNullable(byte[] value) {
        return value == null ? null : value.clone();
    }
}
