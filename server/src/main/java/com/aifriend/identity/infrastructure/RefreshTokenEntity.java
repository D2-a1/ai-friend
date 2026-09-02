package com.aifriend.identity.infrastructure;

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

import com.aifriend.identity.domain.RefreshTokenStatus;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 只保存摘要的刷新令牌 JPA 实体。
 *
 * @author Codex
 * @since 1.0.0
 */
@Entity
@Table(name = "refresh_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshTokenEntity {

    /** 令牌 UUID。 */
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID id;

    /** token family UUID。 */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "family_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID familyId;

    /** 用户 UUID。 */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID userId;

    /** 刷新令牌 SHA-256 摘要。 */
    @Column(name = "token_hash", columnDefinition = "BINARY(32)", nullable = false, unique = true)
    private byte[] tokenHash;

    /** ACTIVE/ROTATED/REVOKED 状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private RefreshTokenStatus status;

    /** 过期时间。 */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 轮换时间。 */
    @Column(name = "rotated_at")
    private Instant rotatedAt;

    /** 撤销时间。 */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** 乐观锁版本。 */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * 创建刷新令牌摘要实体。
     *
     * @param id 令牌 UUID
     * @param familyId family UUID
     * @param userId 用户 UUID
     * @param tokenHash 令牌摘要
     * @param status 令牌状态
     * @param expiresAt 过期时间
     * @param createdAt 创建时间
     */
    public RefreshTokenEntity(
            UUID id,
            UUID familyId,
            UUID userId,
            byte[] tokenHash,
            RefreshTokenStatus status,
            Instant expiresAt,
            Instant createdAt) {
        this.id = id;
        this.familyId = familyId;
        this.userId = userId;
        this.tokenHash = tokenHash.clone();
        this.status = status;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    /**
     * 标记令牌已轮换。
     *
     * @param rotatedAt 轮换时间
     */
    public void markRotated(Instant rotatedAt) {
        this.status = RefreshTokenStatus.ROTATED;
        this.rotatedAt = rotatedAt;
    }

    /**
     * 标记令牌已撤销。
     *
     * @param revokedAt 撤销时间
     */
    public void revoke(Instant revokedAt) {
        this.status = RefreshTokenStatus.REVOKED;
        this.revokedAt = revokedAt;
    }
}
