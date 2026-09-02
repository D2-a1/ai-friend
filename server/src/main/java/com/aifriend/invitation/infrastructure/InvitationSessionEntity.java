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

import com.aifriend.invitation.domain.InvitationSessionStatus;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 只保存 Cookie、CSRF 和 OAuth state 摘要的邀请会话 JPA 实体。
 *
 * @author Codex
 * @since 1.0.0
 */
@Entity
@Table(name = "invitation_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InvitationSessionEntity {

    /** 会话 UUID。 */
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID id;

    /** 关联邀请 UUID，全生命周期一对一。 */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "invitation_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID invitationId;

    /** Cookie 随机令牌 SHA-256 摘要。 */
    @Column(name = "session_token_hash", columnDefinition = "BINARY(32)", nullable = false)
    private byte[] sessionTokenDigest;

    /** CSRF 随机令牌 SHA-256 摘要。 */
    @Column(name = "csrf_token_hash", columnDefinition = "BINARY(32)", nullable = false)
    private byte[] csrfTokenDigest;

    /** OAuth state SHA-256 摘要。 */
    @Column(name = "oauth_state_hash", columnDefinition = "BINARY(32)", nullable = false)
    private byte[] oauthStateDigest;

    /** 明确拒绝操作的幂等键 SHA-256 摘要，尚未拒绝时为空。 */
    @Column(name = "decline_idempotency_key_hash", columnDefinition = "BINARY(32)")
    private byte[] declineIdempotencyKeyDigest;

    /** 明确接受操作的幂等键 SHA-256 摘要，尚未接受时为空。 */
    @Column(name = "accept_idempotency_key_hash", columnDefinition = "BINARY(32)")
    private byte[] acceptIdempotencyKeyDigest;

    /** 已验证亲友微信主体 HMAC，未完成 OAuth 或已迁移时为空。 */
    @Column(name = "oauth_subject_hash", columnDefinition = "BINARY(32)")
    private byte[] oauthSubjectHash;

    /** 已验证亲友微信主体 AES-GCM 密文，未完成 OAuth 或已迁移时为空。 */
    @Column(name = "oauth_subject_cipher", length = 512)
    private byte[] oauthSubjectCipher;

    /** 微信 OAuth 验证时间，可空。 */
    @Column(name = "oauth_verified_at")
    private Instant oauthVerifiedAt;

    /** 已明确接受的政策版本，可空。 */
    @Column(name = "accepted_consent_policy_version", length = 40)
    private String acceptedConsentPolicyVersion;

    /** 受限会话内部状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private InvitationSessionStatus status;

    /** 固定 30 分钟过期时间。 */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 创建时间，UTC。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 邀请撤销等原因导致的终止时间，活动会话为空。 */
    @Column(name = "terminated_at")
    private Instant terminatedAt;

    /** 乐观锁版本。 */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * 创建邀请会话实体。
     *
     * @param id 会话 UUID
     * @param invitationId 邀请 UUID
     * @param sessionTokenDigest Cookie 令牌摘要
     * @param csrfTokenDigest CSRF 令牌摘要
     * @param oauthStateDigest OAuth state 摘要
     * @param declineIdempotencyKeyDigest 明确拒绝幂等键摘要，可空
     * @param acceptIdempotencyKeyDigest 明确接受幂等键摘要，可空
     * @param oauthSubjectHash 已验证微信主体 HMAC，可空
     * @param oauthSubjectCipher 已验证微信主体密文，可空
     * @param oauthVerifiedAt OAuth 验证时间，可空
     * @param acceptedConsentPolicyVersion 已接受政策版本，可空
     * @param status 会话状态
     * @param expiresAt 过期时间
     * @param createdAt 创建时间
     * @param terminatedAt 终止时间，可空
     * @param version 乐观锁版本
     */
    public InvitationSessionEntity(UUID id, UUID invitationId, byte[] sessionTokenDigest,
            byte[] csrfTokenDigest, byte[] oauthStateDigest, byte[] declineIdempotencyKeyDigest,
            byte[] acceptIdempotencyKeyDigest, byte[] oauthSubjectHash, byte[] oauthSubjectCipher,
            Instant oauthVerifiedAt, String acceptedConsentPolicyVersion,
            InvitationSessionStatus status,
            Instant expiresAt, Instant createdAt, Instant terminatedAt, long version) {
        this.id = id;
        this.invitationId = invitationId;
        this.sessionTokenDigest = sessionTokenDigest.clone();
        this.csrfTokenDigest = csrfTokenDigest.clone();
        this.oauthStateDigest = oauthStateDigest.clone();
        this.declineIdempotencyKeyDigest = declineIdempotencyKeyDigest == null
                ? null : declineIdempotencyKeyDigest.clone();
        this.acceptIdempotencyKeyDigest = cloneNullable(acceptIdempotencyKeyDigest);
        this.oauthSubjectHash = cloneNullable(oauthSubjectHash);
        this.oauthSubjectCipher = cloneNullable(oauthSubjectCipher);
        this.oauthVerifiedAt = oauthVerifiedAt;
        this.acceptedConsentPolicyVersion = acceptedConsentPolicyVersion;
        this.status = status;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.terminatedAt = terminatedAt;
        this.version = version;
    }

    /**
     * 获取 Cookie 令牌摘要的防御性副本。
     *
     * @return Cookie 令牌摘要副本
     */
    public byte[] getSessionTokenDigest() {
        return sessionTokenDigest.clone();
    }

    /**
     * 获取 CSRF 令牌摘要的防御性副本。
     *
     * @return CSRF 令牌摘要副本
     */
    public byte[] getCsrfTokenDigest() {
        return csrfTokenDigest.clone();
    }

    /**
     * 获取 OAuth state 摘要的防御性副本。
     *
     * @return OAuth state 摘要副本
     */
    public byte[] getOauthStateDigest() {
        return oauthStateDigest.clone();
    }

    /**
     * 获取明确拒绝幂等键摘要的防御性副本。
     *
     * @return 幂等键摘要副本，尚未拒绝时为空
     */
    public byte[] getDeclineIdempotencyKeyDigest() {
        return declineIdempotencyKeyDigest == null ? null : declineIdempotencyKeyDigest.clone();
    }

    /**
     * 获取明确接受幂等键摘要的防御性副本。
     *
     * @return 幂等键摘要副本，尚未接受时为空
     */
    public byte[] getAcceptIdempotencyKeyDigest() {
        return cloneNullable(acceptIdempotencyKeyDigest);
    }

    /**
     * 获取已验证微信主体 HMAC 的防御性副本。
     *
     * @return 微信主体 HMAC 副本，可空
     */
    public byte[] getOauthSubjectHash() {
        return cloneNullable(oauthSubjectHash);
    }

    /**
     * 获取已验证微信主体密文的防御性副本。
     *
     * @return 微信主体密文副本，可空
     */
    public byte[] getOauthSubjectCipher() {
        return cloneNullable(oauthSubjectCipher);
    }

    private static byte[] cloneNullable(byte[] value) {
        return value == null ? null : value.clone();
    }
}
