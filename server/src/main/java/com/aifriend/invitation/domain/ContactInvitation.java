package com.aifriend.invitation.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 不含 proof 明文的亲友邀请领域快照。
 *
 * @param id 邀请 UUID
 * @param ownerUserId 邀请人 UUID
 * @param proofDigest proof SHA-256 摘要
 * @param status 邀请内部状态
 * @param expiresAt 固定过期时间
 * @param createdAt 创建时间
 * @param createIdempotencyKeyHash 创建幂等键摘要
 * @param revokeIdempotencyKeyHash 撤销幂等键摘要，可空
 * @param version 乐观锁版本
 * @author Codex
 * @since 1.0.0
 */
public record ContactInvitation(
        UUID id,
        UUID ownerUserId,
        byte[] proofDigest,
        InvitationStatus status,
        Instant expiresAt,
        Instant createdAt,
        byte[] createIdempotencyKeyHash,
        byte[] revokeIdempotencyKeyHash,
        long version) {

    /**
     * 防止外部修改摘要数组。
     */
    public ContactInvitation {
        proofDigest = proofDigest.clone();
        createIdempotencyKeyHash = createIdempotencyKeyHash.clone();
        revokeIdempotencyKeyHash = revokeIdempotencyKeyHash == null ? null : revokeIdempotencyKeyHash.clone();
    }

    /**
     * 获取 proof 摘要的防御性副本。
     *
     * @return proof 摘要副本
     */
    @Override
    public byte[] proofDigest() {
        return proofDigest.clone();
    }

    /**
     * 获取创建幂等键摘要的防御性副本。
     *
     * @return 创建幂等键摘要副本
     */
    @Override
    public byte[] createIdempotencyKeyHash() {
        return createIdempotencyKeyHash.clone();
    }

    /**
     * 获取撤销幂等键摘要的防御性副本。
     *
     * @return 撤销幂等键摘要副本，未撤销时为空
     */
    @Override
    public byte[] revokeIdempotencyKeyHash() {
        return revokeIdempotencyKeyHash == null ? null : revokeIdempotencyKeyHash.clone();
    }
}
