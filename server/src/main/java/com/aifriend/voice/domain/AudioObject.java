package com.aifriend.voice.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 临时音频对象领域快照。
 *
 * <p>只保存音频元数据、对象键短期明文和不可逆凭据摘要；原始音频不得进入本对象或 MySQL。
 *
 * @param id 音频对象 UUID
 * @param ownerUserId 当前用户 UUID
 * @param purpose 受限业务用途
 * @param mediaType 声明的允许音频类型
 * @param expectedSizeBytes 预期字节数
 * @param expectedDurationMs 客户端预检时长毫秒数
 * @param expectedSha256 预期音频 SHA-256
 * @param objectKey 服务端生成的私有对象键，只能短期存在于内存
 * @param uploadTokenDigest 上传秘密 SHA-256 摘要
 * @param idempotencyKeyDigest 创建幂等键 SHA-256 摘要
 * @param requestDigest 创建请求语义摘要
 * @param status 业务生命周期状态
 * @param uploadExpiresAt 固定上传凭证过期时间
 * @param retentionUntil 临时音频最长留存截止时间
 * @param storageVersion 已上传对象版本，尚未上传时为空
 * @param uploadedAt 上传完成时间，可空
 * @param consumedAt 业务消费时间，可空
 * @param deletedAt 对象删除时间，可空
 * @param version 乐观锁版本
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @author Codex
 * @since 1.0.0
 */
public record AudioObject(
        UUID id,
        UUID ownerUserId,
        AudioPurpose purpose,
        String mediaType,
        long expectedSizeBytes,
        int expectedDurationMs,
        byte[] expectedSha256,
        String objectKey,
        byte[] uploadTokenDigest,
        byte[] idempotencyKeyDigest,
        byte[] requestDigest,
        AudioObjectStatus status,
        Instant uploadExpiresAt,
        Instant retentionUntil,
        String storageVersion,
        Instant uploadedAt,
        Instant consumedAt,
        Instant deletedAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 创建带防御性摘要副本的音频对象。
     */
    public AudioObject {
        expectedSha256 = expectedSha256.clone();
        uploadTokenDigest = uploadTokenDigest.clone();
        idempotencyKeyDigest = idempotencyKeyDigest.clone();
        requestDigest = requestDigest.clone();
    }

    /**
     * 获取预期音频摘要副本。
     *
     * @return 预期音频摘要副本
     */
    @Override
    public byte[] expectedSha256() {
        return expectedSha256.clone();
    }

    /**
     * 获取上传秘密摘要副本。
     *
     * @return 上传秘密摘要副本
     */
    @Override
    public byte[] uploadTokenDigest() {
        return uploadTokenDigest.clone();
    }

    /**
     * 获取幂等键摘要副本。
     *
     * @return 幂等键摘要副本
     */
    @Override
    public byte[] idempotencyKeyDigest() {
        return idempotencyKeyDigest.clone();
    }

    /**
     * 获取请求语义摘要副本。
     *
     * @return 请求语义摘要副本
     */
    @Override
    public byte[] requestDigest() {
        return requestDigest.clone();
    }

    /**
     * 轮换上传秘密摘要并保留同一音频对象。
     *
     * @param rotatedTokenDigest 新上传秘密摘要
     * @param now 更新时间
     * @return 轮换后的不可变快照
     */
    public AudioObject rotateUploadToken(byte[] rotatedTokenDigest, Instant now) {
        return copy(status, storageVersion, uploadedAt, consumedAt, deletedAt,
                rotatedTokenDigest, now);
    }

    /**
     * 标记本地开发对象上传完成。
     *
     * @param persistedStorageVersion 对象存储版本
     * @param now 上传完成时间
     * @return 上传后的不可变快照
     */
    public AudioObject markUploaded(String persistedStorageVersion, Instant now) {
        return copy(status, persistedStorageVersion, now, consumedAt, deletedAt,
                uploadTokenDigest, now);
    }

    /**
     * 标记音频对象已被业务接口一次性消费。
     *
     * @param now 业务消费时间
     * @return 已消费的不可变快照
     */
    public AudioObject markConsumed(Instant now) {
        return copy(AudioObjectStatus.CONSUMED, storageVersion, uploadedAt,
                now, deletedAt, uploadTokenDigest, now);
    }

    /**
     * 将未消费对象推进到过期状态。
     *
     * @param now 过期处理时间
     * @return 过期后的不可变快照
     */
    public AudioObject expire(Instant now) {
        return copy(AudioObjectStatus.EXPIRED, storageVersion, uploadedAt,
                consumedAt, deletedAt, uploadTokenDigest, now);
    }

    /**
     * 标记对象存储内容已删除。
     *
     * @param now 删除完成时间
     * @return 删除后的不可变快照
     */
    public AudioObject markDeleted(Instant now) {
        return copy(AudioObjectStatus.DELETED, null, uploadedAt,
                consumedAt, now, uploadTokenDigest, now);
    }

    /**
     * 返回不包含对象键和摘要的诊断文本。
     *
     * @return 脱敏诊断文本
     */
    @Override
    public String toString() {
        return "AudioObject[purpose=" + purpose
                + ", mediaType=" + mediaType
                + ", expectedSizeBytes=" + expectedSizeBytes
                + ", status=" + status
                + ", version=" + version + "]";
    }

    private AudioObject copy(
            AudioObjectStatus nextStatus,
            String nextStorageVersion,
            Instant nextUploadedAt,
            Instant nextConsumedAt,
            Instant nextDeletedAt,
            byte[] nextUploadTokenDigest,
            Instant nextUpdatedAt) {
        return new AudioObject(
                id, ownerUserId, purpose, mediaType, expectedSizeBytes,
                expectedDurationMs, expectedSha256, objectKey, nextUploadTokenDigest,
                idempotencyKeyDigest, requestDigest, nextStatus, uploadExpiresAt,
                retentionUntil, nextStorageVersion, nextUploadedAt, nextConsumedAt,
                nextDeletedAt, version, createdAt, nextUpdatedAt);
    }
}
