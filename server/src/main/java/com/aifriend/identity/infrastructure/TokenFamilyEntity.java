package com.aifriend.identity.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 刷新令牌 family JPA 实体。
 *
 * @author Codex
 * @since 1.0.0
 */
@Entity
@Table(name = "token_family")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TokenFamilyEntity {

    /** family UUID。 */
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID id;

    /** 用户 UUID。 */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID userId;

    /** 绑定的 Android Keystore 公钥 SHA-256 摘要；旧 family 可空。 */
    @Column(name = "device_public_key_sha256", columnDefinition = "BINARY(32)")
    private byte[] devicePublicKeySha256;

    /** family 状态：ACTIVE/REVOKED。 */
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 撤销时间。 */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** 乐观锁版本。 */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * 创建 ACTIVE token family。
     *
     * @param id family UUID
     * @param userId 用户 UUID
     * @param devicePublicKeySha256 已验证设备公钥摘要；门禁关闭时为空
     * @param createdAt 创建时间
     */
    public TokenFamilyEntity(
            UUID id,
            UUID userId,
            byte[] devicePublicKeySha256,
            Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.devicePublicKeySha256 = devicePublicKeySha256 == null
                ? null : devicePublicKeySha256.clone();
        this.status = "ACTIVE";
        this.createdAt = createdAt;
    }

    /**
     * 撤销 family。
     *
     * @param revokedAt 撤销时间
     */
    public void revoke(Instant revokedAt) {
        this.status = "REVOKED";
        this.revokedAt = revokedAt;
    }
}
