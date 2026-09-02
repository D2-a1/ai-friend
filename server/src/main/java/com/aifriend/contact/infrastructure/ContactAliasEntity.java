package com.aifriend.contact.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.aifriend.contact.domain.ContactAliasStatus;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 联系人称呼 JPA 实体，展示文字、提示和声学模板仅保存密文。
 *
 * @author Codex
 * @since 1.0.0
 */
@Entity
@Table(name = "contact_alias")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContactAliasEntity {

    /** 称呼 UUID。 */
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID id;

    /** 联系人绑定 UUID。 */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "binding_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID bindingId;

    /** 老人账号 UUID。 */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "owner_user_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID ownerUserId;

    /** 辅助展示文字密文，删除后为空。 */
    @Column(name = "display_text_cipher", length = 512)
    private byte[] displayTextCipher;

    /** 可选音素提示密文，删除后为空。 */
    @Column(name = "phonetic_hint_cipher", length = 512)
    private byte[] phoneticHintCipher;

    /** 方言代码。 */
    @Column(name = "dialect_code", length = 40, nullable = false)
    private String dialectCode;

    /** 方言包版本。 */
    @Column(name = "dialect_package_version", length = 60, nullable = false)
    private String dialectPackageVersion;

    /** 声学模板模型版本。 */
    @Column(name = "template_model_version", length = 60, nullable = false)
    private String templateModelVersion;

    /** 注册冲突阈值版本。 */
    @Column(name = "threshold_version", length = 60, nullable = false)
    private String thresholdVersion;

    /** AES-GCM 加密后的发音内容模板，删除后为空。 */
    @Lob
    @Column(name = "template_cipher", columnDefinition = "MEDIUMBLOB")
    private byte[] templateCipher;

    /** 模板完整性摘要，删除后为空。 */
    @Column(name = "template_digest", columnDefinition = "BINARY(32)")
    private byte[] templateDigest;

    /** 生命周期状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ContactAliasStatus status;

    /** 创建幂等键摘要。 */
    @Column(name = "create_idempotency_key_hash", columnDefinition = "BINARY(32)", nullable = false)
    private byte[] createIdempotencyKeyHash;

    /** 创建请求语义摘要。 */
    @Column(name = "create_request_hash", columnDefinition = "BINARY(32)", nullable = false)
    private byte[] createRequestHash;

    /** 删除幂等键摘要，可空。 */
    @Column(name = "delete_idempotency_key_hash", columnDefinition = "BINARY(32)")
    private byte[] deleteIdempotencyKeyHash;

    /** 删除请求语义摘要，可空。 */
    @Column(name = "delete_request_hash", columnDefinition = "BINARY(32)")
    private byte[] deleteRequestHash;

    /** 乐观锁版本。 */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 删除时间，可空。 */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * 创建联系人称呼实体。
     *
     * @param id 称呼 UUID
     * @param bindingId 联系人绑定 UUID
     * @param ownerUserId owner UUID
     * @param displayTextCipher 展示文字密文，可空
     * @param phoneticHintCipher 音素提示密文，可空
     * @param dialectCode 方言代码
     * @param dialectPackageVersion 方言包版本
     * @param templateModelVersion 模板模型版本
     * @param thresholdVersion 阈值版本
     * @param templateCipher 模板密文，可空
     * @param templateDigest 模板摘要，可空
     * @param status 生命周期状态
     * @param createIdempotencyKeyHash 创建幂等键摘要
     * @param createRequestHash 创建请求摘要
     * @param deleteIdempotencyKeyHash 删除幂等键摘要，可空
     * @param deleteRequestHash 删除请求摘要，可空
     * @param version 乐观锁版本
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     * @param deletedAt 删除时间，可空
     */
    public ContactAliasEntity(
            UUID id,
            UUID bindingId,
            UUID ownerUserId,
            byte[] displayTextCipher,
            byte[] phoneticHintCipher,
            String dialectCode,
            String dialectPackageVersion,
            String templateModelVersion,
            String thresholdVersion,
            byte[] templateCipher,
            byte[] templateDigest,
            ContactAliasStatus status,
            byte[] createIdempotencyKeyHash,
            byte[] createRequestHash,
            byte[] deleteIdempotencyKeyHash,
            byte[] deleteRequestHash,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt) {
        this.id = id;
        this.bindingId = bindingId;
        this.ownerUserId = ownerUserId;
        this.displayTextCipher = cloneNullable(displayTextCipher);
        this.phoneticHintCipher = cloneNullable(phoneticHintCipher);
        this.dialectCode = dialectCode;
        this.dialectPackageVersion = dialectPackageVersion;
        this.templateModelVersion = templateModelVersion;
        this.thresholdVersion = thresholdVersion;
        this.templateCipher = cloneNullable(templateCipher);
        this.templateDigest = cloneNullable(templateDigest);
        this.status = status;
        this.createIdempotencyKeyHash = createIdempotencyKeyHash.clone();
        this.createRequestHash = createRequestHash.clone();
        this.deleteIdempotencyKeyHash = cloneNullable(deleteIdempotencyKeyHash);
        this.deleteRequestHash = cloneNullable(deleteRequestHash);
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
    }

    /**
     * 获取展示文字密文的防御性副本。
     *
     * @return 展示文字密文副本，可空
     */
    public byte[] getDisplayTextCipher() { return cloneNullable(displayTextCipher); }

    /**
     * 获取音素提示密文的防御性副本。
     *
     * @return 音素提示密文副本，可空
     */
    public byte[] getPhoneticHintCipher() { return cloneNullable(phoneticHintCipher); }

    /**
     * 获取模板密文的防御性副本。
     *
     * @return 模板密文副本，可空
     */
    public byte[] getTemplateCipher() { return cloneNullable(templateCipher); }

    /**
     * 获取模板摘要的防御性副本。
     *
     * @return 模板摘要副本，可空
     */
    public byte[] getTemplateDigest() { return cloneNullable(templateDigest); }

    /**
     * 获取创建幂等键摘要的防御性副本。
     *
     * @return 创建幂等键摘要副本
     */
    public byte[] getCreateIdempotencyKeyHash() { return createIdempotencyKeyHash.clone(); }

    /**
     * 获取创建请求摘要的防御性副本。
     *
     * @return 创建请求摘要副本
     */
    public byte[] getCreateRequestHash() { return createRequestHash.clone(); }

    /**
     * 获取删除幂等键摘要的防御性副本。
     *
     * @return 删除幂等键摘要副本，可空
     */
    public byte[] getDeleteIdempotencyKeyHash() { return cloneNullable(deleteIdempotencyKeyHash); }

    /**
     * 获取删除请求摘要的防御性副本。
     *
     * @return 删除请求摘要副本，可空
     */
    public byte[] getDeleteRequestHash() { return cloneNullable(deleteRequestHash); }

    private static byte[] cloneNullable(byte[] value) {
        return value == null ? null : value.clone();
    }
}
