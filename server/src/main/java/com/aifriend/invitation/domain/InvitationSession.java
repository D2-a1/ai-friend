package com.aifriend.invitation.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 不含 Cookie、CSRF 和 OAuth state 明文的受限邀请会话快照。
 *
 * @param id 会话 UUID
 * @param invitationId 邀请 UUID
 * @param sessionTokenDigest Cookie 随机令牌 SHA-256 摘要
 * @param csrfTokenDigest CSRF 随机令牌 SHA-256 摘要
 * @param oauthStateDigest OAuth state SHA-256 摘要
 * @param declineIdempotencyKeyDigest 明确拒绝幂等键 SHA-256 摘要，未拒绝时为空
 * @param acceptIdempotencyKeyDigest 明确接受幂等键 SHA-256 摘要，未接受时为空
 * @param oauthSubjectHash 已验证亲友微信主体 HMAC，未完成 OAuth 或已迁移时为空
 * @param oauthSubjectCipher 已验证亲友微信主体密文，未完成 OAuth 或已迁移时为空
 * @param oauthVerifiedAt 微信 OAuth 验证时间，可空
 * @param acceptedConsentPolicyVersion 已接受政策版本，可空
 * @param status 会话内部状态
 * @param expiresAt 固定过期时间
 * @param createdAt 创建时间
 * @param terminatedAt 终止时间，活动会话为空
 * @param version 乐观锁版本
 * @author Codex
 * @since 1.0.0
 */
public record InvitationSession(
        UUID id,
        UUID invitationId,
        byte[] sessionTokenDigest,
        byte[] csrfTokenDigest,
        byte[] oauthStateDigest,
        byte[] declineIdempotencyKeyDigest,
        byte[] acceptIdempotencyKeyDigest,
        byte[] oauthSubjectHash,
        byte[] oauthSubjectCipher,
        Instant oauthVerifiedAt,
        String acceptedConsentPolicyVersion,
        InvitationSessionStatus status,
        Instant expiresAt,
        Instant createdAt,
        Instant terminatedAt,
        long version) {

    /**
     * 防止外部修改会话摘要数组。
     */
    public InvitationSession {
        sessionTokenDigest = sessionTokenDigest.clone();
        csrfTokenDigest = csrfTokenDigest.clone();
        oauthStateDigest = oauthStateDigest.clone();
        declineIdempotencyKeyDigest = declineIdempotencyKeyDigest == null
                ? null : declineIdempotencyKeyDigest.clone();
        acceptIdempotencyKeyDigest = cloneNullable(acceptIdempotencyKeyDigest);
        oauthSubjectHash = cloneNullable(oauthSubjectHash);
        oauthSubjectCipher = cloneNullable(oauthSubjectCipher);
    }

    /**
     * 创建不含 OAuth 身份和接受证据的兼容会话快照。
     *
     * @param id 会话 UUID
     * @param invitationId 邀请 UUID
     * @param sessionTokenDigest Cookie 摘要
     * @param csrfTokenDigest CSRF 摘要
     * @param oauthStateDigest OAuth state 摘要
     * @param declineIdempotencyKeyDigest 拒绝幂等摘要，可空
     * @param status 会话状态
     * @param expiresAt 过期时间
     * @param createdAt 创建时间
     * @param terminatedAt 终止时间，可空
     * @param version 乐观锁版本
     */
    public InvitationSession(
            UUID id,
            UUID invitationId,
            byte[] sessionTokenDigest,
            byte[] csrfTokenDigest,
            byte[] oauthStateDigest,
            byte[] declineIdempotencyKeyDigest,
            InvitationSessionStatus status,
            Instant expiresAt,
            Instant createdAt,
            Instant terminatedAt,
            long version) {
        this(id, invitationId, sessionTokenDigest, csrfTokenDigest, oauthStateDigest,
                declineIdempotencyKeyDigest, null, null, null, null, null,
                status, expiresAt, createdAt, terminatedAt, version);
    }

    /**
     * 获取 Cookie 令牌摘要的防御性副本。
     *
     * @return Cookie 令牌摘要副本
     */
    @Override
    public byte[] sessionTokenDigest() {
        return sessionTokenDigest.clone();
    }

    /**
     * 获取 CSRF 令牌摘要的防御性副本。
     *
     * @return CSRF 令牌摘要副本
     */
    @Override
    public byte[] csrfTokenDigest() {
        return csrfTokenDigest.clone();
    }

    /**
     * 获取 OAuth state 摘要的防御性副本。
     *
     * @return OAuth state 摘要副本
     */
    @Override
    public byte[] oauthStateDigest() {
        return oauthStateDigest.clone();
    }

    /**
     * 获取明确拒绝幂等键摘要的防御性副本。
     *
     * @return 幂等键摘要副本，尚未拒绝时为空
     */
    @Override
    public byte[] declineIdempotencyKeyDigest() {
        return declineIdempotencyKeyDigest == null ? null : declineIdempotencyKeyDigest.clone();
    }

    /**
     * 获取明确接受幂等键摘要的防御性副本。
     *
     * @return 幂等键摘要副本，尚未接受时为空
     */
    @Override
    public byte[] acceptIdempotencyKeyDigest() {
        return cloneNullable(acceptIdempotencyKeyDigest);
    }

    /**
     * 获取已验证微信主体 HMAC 的防御性副本。
     *
     * @return 微信主体 HMAC 副本，不存在或已迁移时为空
     */
    @Override
    public byte[] oauthSubjectHash() {
        return cloneNullable(oauthSubjectHash);
    }

    /**
     * 获取已验证微信主体密文的防御性副本。
     *
     * @return 微信主体密文副本，不存在或已迁移时为空
     */
    @Override
    public byte[] oauthSubjectCipher() {
        return cloneNullable(oauthSubjectCipher);
    }

    private static byte[] cloneNullable(byte[] value) {
        return value == null ? null : value.clone();
    }
}
