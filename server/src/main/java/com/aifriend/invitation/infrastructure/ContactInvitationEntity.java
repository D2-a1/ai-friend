package com.aifriend.invitation.infrastructure;

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

import com.aifriend.invitation.domain.InvitationStatus;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 不保存 proof 明文的亲友邀请 JPA 实体。
 *
 * @author Codex
 * @since 1.0.0
 */
@Entity
@Table(name = "contact_invitation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContactInvitationEntity {

    /** 邀请 UUID，数据库保存为 BINARY(16)。 */
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID id;

    /** 邀请人 UUID。 */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "owner_user_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID ownerUserId;

    /** 256 位 proof 的 SHA-256 摘要。 */
    @Column(name = "proof_digest", columnDefinition = "BINARY(32)", nullable = false)
    private byte[] proofDigest;

    /** 邀请内部状态，终态不可恢复。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 24, nullable = false)
    private InvitationStatus status;

    /** 固定 24 小时过期时间。 */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 创建时间，UTC。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 创建操作幂等键 SHA-256 摘要。 */
    @Column(name = "create_idempotency_key_hash", columnDefinition = "BINARY(32)", nullable = false)
    private byte[] createIdempotencyKeyHash;

    /** 撤销操作幂等键 SHA-256 摘要。 */
    @Column(name = "revoke_idempotency_key_hash", columnDefinition = "BINARY(32)")
    private byte[] revokeIdempotencyKeyHash;

    /** 乐观锁版本。 */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * 创建邀请实体。
     *
     * @param id 邀请 UUID
     * @param ownerUserId 邀请人 UUID
     * @param proofDigest proof 摘要
     * @param status 邀请状态
     * @param expiresAt 过期时间
     * @param createdAt 创建时间
     * @param createIdempotencyKeyHash 创建幂等键摘要
     * @param revokeIdempotencyKeyHash 撤销幂等键摘要
     * @param version 乐观锁版本
     */
    public ContactInvitationEntity(UUID id, UUID ownerUserId, byte[] proofDigest, InvitationStatus status,
            Instant expiresAt, Instant createdAt, byte[] createIdempotencyKeyHash,
            byte[] revokeIdempotencyKeyHash, long version) {
        this.id = id;
        this.ownerUserId = ownerUserId;
        this.proofDigest = proofDigest.clone();
        this.status = status;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.createIdempotencyKeyHash = createIdempotencyKeyHash.clone();
        this.revokeIdempotencyKeyHash = revokeIdempotencyKeyHash == null ? null : revokeIdempotencyKeyHash.clone();
        this.version = version;
    }

    /**
     * 获取 proof 摘要的防御性副本。
     *
     * @return proof 摘要副本
     */
    public byte[] getProofDigest() {
        return proofDigest.clone();
    }

    /**
     * 获取创建幂等键摘要的防御性副本。
     *
     * @return 创建幂等键摘要副本
     */
    public byte[] getCreateIdempotencyKeyHash() {
        return createIdempotencyKeyHash.clone();
    }

    /**
     * 获取撤销幂等键摘要的防御性副本。
     *
     * @return 摘要副本，未撤销时为空
     */
    public byte[] getRevokeIdempotencyKeyHash() {
        return revokeIdempotencyKeyHash == null ? null : revokeIdempotencyKeyHash.clone();
    }
}
