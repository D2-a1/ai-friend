package com.aifriend.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 持久化刷新令牌的无明文快照。
 *
 * @param id 令牌内部 UUID
 * @param familyId token family UUID
 * @param userId 用户 UUID
 * @param devicePublicKeySha256 token family 绑定的设备公钥摘要；旧 family 可空
 * @param tokenHash 令牌 SHA-256 摘要
 * @param status 令牌状态
 * @param expiresAt 过期时间
 * @author Codex
 * @since 1.0.0
 */
public record StoredRefreshToken(
        UUID id,
        UUID familyId,
        UUID userId,
        byte[] devicePublicKeySha256,
        byte[] tokenHash,
        RefreshTokenStatus status,
        Instant expiresAt) {
}
