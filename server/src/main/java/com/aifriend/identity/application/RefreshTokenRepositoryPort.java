package com.aifriend.identity.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.aifriend.identity.domain.StoredRefreshToken;

/**
 * 刷新令牌 family 与令牌记录持久化端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface RefreshTokenRepositoryPort {

    /**
     * 新建 token family。
     *
     * @param familyId family UUID
     * @param userId 用户 UUID
     * @param devicePublicKeySha256 已验证设备公钥摘要；门禁关闭时为空
     * @param createdAt 创建时间
     */
    void createFamily(UUID familyId, UUID userId, byte[] devicePublicKeySha256, Instant createdAt);

    /**
     * 保存令牌摘要记录。
     *
     * @param token 无明文令牌快照
     * @param createdAt 创建时间
     */
    void saveToken(StoredRefreshToken token, Instant createdAt);

    /**
     * 按摘要加锁查询，串行化同一令牌轮换。
     *
     * @param tokenHash 刷新令牌摘要
     * @return 无明文令牌快照
     */
    Optional<StoredRefreshToken> findByTokenHashForUpdate(byte[] tokenHash);

    /**
     * 将当前令牌标记为已轮换。
     *
     * @param tokenId 令牌 UUID
     * @param rotatedAt 轮换时间
     */
    void markRotated(UUID tokenId, Instant rotatedAt);

    /**
     * 撤销整个 token family 及其全部令牌。
     *
     * @param familyId family UUID
     * @param revokedAt 撤销时间
     */
    void revokeFamily(UUID familyId, Instant revokedAt);
}
