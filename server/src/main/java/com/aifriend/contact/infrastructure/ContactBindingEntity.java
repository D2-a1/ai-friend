package com.aifriend.contact.infrastructure;

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

import com.aifriend.contact.domain.ContactStatus;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 联系人绑定 JPA 实体，敏感微信字段只保存密文或 HMAC。
 *
 * @author Codex
 * @since 1.0.0
 */
@Entity
@Table(name = "contact_binding")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContactBindingEntity {

    /** 绑定 UUID。 */
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID id;

    /** 老人账号 UUID。 */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "owner_user_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID ownerUserId;

    /** 亲友微信主体 HMAC 查询键。 */
    @Column(name = "contact_subject_hash", columnDefinition = "BINARY(32)", nullable = false)
    private byte[] contactSubjectHash;

    /** 亲友微信主体 AES-GCM 密文，解绑后清空。 */
    @Column(name = "contact_subject_cipher", length = 512)
    private byte[] contactSubjectCipher;

    /** 本机稳定定位密文，未验证时为空。 */
    @Column(name = "wechat_locator_cipher", length = 4096)
    private byte[] wechatLocatorCipher;

    /** 本机稳定定位 HMAC，未验证时为空。 */
    @Column(name = "wechat_locator_hash", columnDefinition = "BINARY(32)")
    private byte[] wechatLocatorHash;

    /** 当前微信备注密文，可空。 */
    @Column(name = "remark_cipher", length = 512)
    private byte[] remarkCipher;

    /** 最近验证使用的微信版本，可空。 */
    @Column(name = "wechat_version", length = 40)
    private String wechatVersion;

    /** 本机验证规则版本，可空。 */
    @Column(name = "local_verification_version", length = 40)
    private String localVerificationVersion;

    /** 最近本机验证幂等键 SHA-256，可空。 */
    @Column(name = "verification_idempotency_key_hash", columnDefinition = "BINARY(32)")
    private byte[] verificationIdempotencyKeyHash;

    /** 最近本机验证请求指纹 SHA-256，可空。 */
    @Column(name = "verification_request_hash", columnDefinition = "BINARY(32)")
    private byte[] verificationRequestHash;

    /** 最近解绑幂等键 SHA-256，可空。 */
    @Column(name = "unbind_idempotency_key_hash", columnDefinition = "BINARY(32)")
    private byte[] unbindIdempotencyKeyHash;

    /** 最近解绑请求指纹 SHA-256，可空。 */
    @Column(name = "unbind_request_hash", columnDefinition = "BINARY(32)")
    private byte[] unbindRequestHash;

    /** 最近本机验证时间，可空。 */
    @Column(name = "verified_at")
    private Instant verifiedAt;

    /** 可选关系说明。 */
    @Column(name = "relationship", length = 30)
    private String relationship;

    /** 亲友明确同意时使用的政策版本，可空。 */
    @Column(name = "consent_policy_version", length = 40)
    private String consentPolicyVersion;

    /** 亲友明确同意时间，可空。 */
    @Column(name = "consented_at")
    private Instant consentedAt;

    /** 联系人绑定状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private ContactStatus status;

    /** 创建该绑定的账号 UUID。 */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "created_by", columnDefinition = "BINARY(16)", nullable = false)
    private UUID createdBy;

    /** 乐观锁版本，数据库从 0 开始。 */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** 创建时间，UTC。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 更新时间，UTC。 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 解除时间，可空。 */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * 创建联系人绑定实体。
     *
     * @param id 绑定 UUID
     * @param ownerUserId owner UUID
     * @param contactSubjectHash 微信主体 HMAC
     * @param contactSubjectCipher 微信主体密文
     * @param wechatLocatorCipher 稳定定位密文，可空
     * @param wechatLocatorHash 稳定定位 HMAC，可空
     * @param remarkCipher 微信备注密文，可空
     * @param wechatVersion 微信版本，可空
     * @param localVerificationVersion 本机验证规则版本，可空
     * @param verificationIdempotencyKeyHash 最近验证幂等键摘要，可空
     * @param verificationRequestHash 最近验证请求指纹，可空
     * @param unbindIdempotencyKeyHash 最近解绑幂等键摘要，可空
     * @param unbindRequestHash 最近解绑请求指纹，可空
     * @param verifiedAt 最近验证时间，可空
     * @param relationship 关系说明，可空
     * @param consentPolicyVersion 亲友同意政策版本，可空
     * @param consentedAt 亲友明确同意时间，可空
     * @param status 绑定状态
     * @param createdBy 创建账号 UUID
     * @param version 乐观锁版本
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     * @param revokedAt 解除时间，可空
     */
    public ContactBindingEntity(
            UUID id,
            UUID ownerUserId,
            byte[] contactSubjectHash,
            byte[] contactSubjectCipher,
            byte[] wechatLocatorCipher,
            byte[] wechatLocatorHash,
            byte[] remarkCipher,
            String wechatVersion,
            String localVerificationVersion,
            byte[] verificationIdempotencyKeyHash,
            byte[] verificationRequestHash,
            byte[] unbindIdempotencyKeyHash,
            byte[] unbindRequestHash,
            Instant verifiedAt,
            String relationship,
            String consentPolicyVersion,
            Instant consentedAt,
            ContactStatus status,
            UUID createdBy,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Instant revokedAt) {
        this.id = id;
        this.ownerUserId = ownerUserId;
        this.contactSubjectHash = contactSubjectHash.clone();
        this.contactSubjectCipher = cloneNullable(contactSubjectCipher);
        this.wechatLocatorCipher = cloneNullable(wechatLocatorCipher);
        this.wechatLocatorHash = cloneNullable(wechatLocatorHash);
        this.remarkCipher = cloneNullable(remarkCipher);
        this.wechatVersion = wechatVersion;
        this.localVerificationVersion = localVerificationVersion;
        this.verificationIdempotencyKeyHash = cloneNullable(verificationIdempotencyKeyHash);
        this.verificationRequestHash = cloneNullable(verificationRequestHash);
        this.unbindIdempotencyKeyHash = cloneNullable(unbindIdempotencyKeyHash);
        this.unbindRequestHash = cloneNullable(unbindRequestHash);
        this.verifiedAt = verifiedAt;
        this.relationship = relationship;
        this.consentPolicyVersion = consentPolicyVersion;
        this.consentedAt = consentedAt;
        this.status = status;
        this.createdBy = createdBy;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.revokedAt = revokedAt;
    }

    /**
     * 返回微信主体 HMAC 的防御性副本。
     *
     * @return 微信主体 HMAC 副本
     */
    public byte[] getContactSubjectHash() {
        return contactSubjectHash.clone();
    }

    /**
     * 返回微信主体密文的防御性副本。
     *
     * @return 微信主体密文副本
     */
    public byte[] getContactSubjectCipher() {
        return cloneNullable(contactSubjectCipher);
    }

    /**
     * 返回稳定定位密文的防御性副本。
     *
     * @return 稳定定位密文副本，可空
     */
    public byte[] getWechatLocatorCipher() {
        return cloneNullable(wechatLocatorCipher);
    }

    /**
     * 返回稳定定位 HMAC 的防御性副本。
     *
     * @return 稳定定位 HMAC 副本，可空
     */
    public byte[] getWechatLocatorHash() {
        return cloneNullable(wechatLocatorHash);
    }

    /**
     * 返回微信备注密文的防御性副本。
     *
     * @return 微信备注密文副本，可空
     */
    public byte[] getRemarkCipher() {
        return cloneNullable(remarkCipher);
    }

    /**
     * 返回最近验证幂等键摘要的防御性副本。
     *
     * @return 幂等键 SHA-256 副本，可空
     */
    public byte[] getVerificationIdempotencyKeyHash() {
        return cloneNullable(verificationIdempotencyKeyHash);
    }

    /**
     * 返回最近验证请求指纹的防御性副本。
     *
     * @return 请求指纹 SHA-256 副本，可空
     */
    public byte[] getVerificationRequestHash() {
        return cloneNullable(verificationRequestHash);
    }

    /**
     * 返回最近解绑幂等键摘要的防御性副本。
     *
     * @return 解绑幂等键 SHA-256 副本，可空
     */
    public byte[] getUnbindIdempotencyKeyHash() {
        return cloneNullable(unbindIdempotencyKeyHash);
    }

    /**
     * 返回最近解绑请求指纹的防御性副本。
     *
     * @return 解绑请求指纹 SHA-256 副本，可空
     */
    public byte[] getUnbindRequestHash() {
        return cloneNullable(unbindRequestHash);
    }

    private static byte[] cloneNullable(byte[] value) {
        return value == null ? null : value.clone();
    }
}
