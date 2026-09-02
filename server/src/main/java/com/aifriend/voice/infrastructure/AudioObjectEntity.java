package com.aifriend.voice.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.aifriend.voice.domain.AudioObjectStatus;
import com.aifriend.voice.domain.AudioPurpose;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 临时音频对象 JPA 实体，只保存元数据、密文和不可逆摘要。
 *
 * @author Codex
 * @since 1.0.0
 */
@Entity
@Table(name = "audio_object")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AudioObjectEntity {

    /** 音频对象 UUID。 */
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID id;

    /** 对象所属账号 UUID。 */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "owner_user_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID ownerUserId;

    /** 受限业务用途。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", length = 40, nullable = false)
    private AudioPurpose purpose;

    /** 允许上传的媒体类型。 */
    @Column(name = "media_type", length = 40, nullable = false)
    private String mediaType;

    /** 预期音频字节数。 */
    @Column(name = "expected_size_bytes", nullable = false)
    private long expectedSizeBytes;

    /** 客户端预检时长，单位毫秒。 */
    @Column(name = "expected_duration_ms", nullable = false)
    private int expectedDurationMs;

    /** 预期音频 SHA-256。 */
    @Column(name = "expected_sha256", columnDefinition = "BINARY(32)", nullable = false)
    private byte[] expectedSha256;

    /** 私有对象键 AES-GCM 密文。 */
    @Column(name = "object_key_cipher", length = 1024, nullable = false)
    private byte[] objectKeyCipher;

    /** 当前上传秘密 SHA-256。 */
    @Column(name = "upload_token_hash", columnDefinition = "BINARY(32)", nullable = false)
    private byte[] uploadTokenHash;

    /** 创建幂等键 SHA-256。 */
    @Column(name = "idempotency_key_hash", columnDefinition = "BINARY(32)", nullable = false)
    private byte[] idempotencyKeyHash;

    /** 创建请求语义 SHA-256。 */
    @Column(name = "request_hash", columnDefinition = "BINARY(32)", nullable = false)
    private byte[] requestHash;

    /** 对象业务状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private AudioObjectStatus status;

    /** 固定上传凭证过期时间。 */
    @Column(name = "upload_expires_at", nullable = false)
    private Instant uploadExpiresAt;

    /** 临时音频最晚保留时间。 */
    @Column(name = "retention_until", nullable = false)
    private Instant retentionUntil;

    /** 对象存储版本标识，上传前为空。 */
    @Column(name = "storage_version", length = 128)
    private String storageVersion;

    /** 上传完成时间，可空。 */
    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    /** 业务一次性消费时间，可空。 */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    /** 对象内容删除时间，可空。 */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** 乐观锁版本。 */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** 创建时间，UTC。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 更新时间，UTC。 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 创建临时音频对象实体。
     *
     * @param id 音频对象 UUID
     * @param ownerUserId 所属账号 UUID
     * @param purpose 受限用途
     * @param mediaType 允许的媒体类型
     * @param expectedSizeBytes 预期字节数
     * @param expectedDurationMs 预期时长毫秒数
     * @param expectedSha256 预期音频摘要
     * @param objectKeyCipher 私有对象键密文
     * @param uploadTokenHash 上传秘密摘要
     * @param idempotencyKeyHash 创建幂等键摘要
     * @param requestHash 创建请求摘要
     * @param status 对象状态
     * @param uploadExpiresAt 上传凭证过期时间
     * @param retentionUntil 最晚保留时间
     * @param storageVersion 对象存储版本，可空
     * @param uploadedAt 上传完成时间，可空
     * @param consumedAt 业务消费时间，可空
     * @param deletedAt 内容删除时间，可空
     * @param version 乐观锁版本
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public AudioObjectEntity(
            UUID id,
            UUID ownerUserId,
            AudioPurpose purpose,
            String mediaType,
            long expectedSizeBytes,
            int expectedDurationMs,
            byte[] expectedSha256,
            byte[] objectKeyCipher,
            byte[] uploadTokenHash,
            byte[] idempotencyKeyHash,
            byte[] requestHash,
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
        this.id = id;
        this.ownerUserId = ownerUserId;
        this.purpose = purpose;
        this.mediaType = mediaType;
        this.expectedSizeBytes = expectedSizeBytes;
        this.expectedDurationMs = expectedDurationMs;
        this.expectedSha256 = expectedSha256.clone();
        this.objectKeyCipher = objectKeyCipher.clone();
        this.uploadTokenHash = uploadTokenHash.clone();
        this.idempotencyKeyHash = idempotencyKeyHash.clone();
        this.requestHash = requestHash.clone();
        this.status = status;
        this.uploadExpiresAt = uploadExpiresAt;
        this.retentionUntil = retentionUntil;
        this.storageVersion = storageVersion;
        this.uploadedAt = uploadedAt;
        this.consumedAt = consumedAt;
        this.deletedAt = deletedAt;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 返回预期音频摘要的防御性副本。
     *
     * @return 预期音频摘要副本
     */
    public byte[] getExpectedSha256() {
        return expectedSha256.clone();
    }

    /**
     * 返回私有对象键密文的防御性副本。
     *
     * @return 私有对象键密文副本
     */
    public byte[] getObjectKeyCipher() {
        return objectKeyCipher.clone();
    }

    /**
     * 返回上传秘密摘要的防御性副本。
     *
     * @return 上传秘密摘要副本
     */
    public byte[] getUploadTokenHash() {
        return uploadTokenHash.clone();
    }

    /**
     * 返回创建幂等键摘要的防御性副本。
     *
     * @return 创建幂等键摘要副本
     */
    public byte[] getIdempotencyKeyHash() {
        return idempotencyKeyHash.clone();
    }

    /**
     * 返回创建请求摘要的防御性副本。
     *
     * @return 创建请求摘要副本
     */
    public byte[] getRequestHash() {
        return requestHash.clone();
    }
}
