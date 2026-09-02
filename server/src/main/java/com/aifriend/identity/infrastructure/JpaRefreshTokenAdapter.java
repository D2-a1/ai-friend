package com.aifriend.identity.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.aifriend.identity.application.RefreshTokenRepositoryPort;
import com.aifriend.identity.domain.StoredRefreshToken;

/**
 * JPA 刷新令牌持久化适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JpaRefreshTokenAdapter implements RefreshTokenRepositoryPort {

    private final TokenFamilyJpaRepository familyRepository;
    private final RefreshTokenJpaRepository tokenRepository;

    /**
     * 创建刷新令牌持久化适配器。
     *
     * @param familyRepository token family Repository
     * @param tokenRepository 刷新令牌 Repository
     */
    public JpaRefreshTokenAdapter(
            TokenFamilyJpaRepository familyRepository,
            RefreshTokenJpaRepository tokenRepository) {
        this.familyRepository = familyRepository;
        this.tokenRepository = tokenRepository;
    }

    /**
     * 创建新的活动 token family。
     *
     * @param familyId family UUID
     * @param userId 用户 UUID
     * @param createdAt 创建时间
     */
    @Override
    public void createFamily(
            UUID familyId,
            UUID userId,
            byte[] devicePublicKeySha256,
            Instant createdAt) {
        familyRepository.save(new TokenFamilyEntity(
                familyId, userId, devicePublicKeySha256, createdAt));
    }

    /**
     * 保存不含明文的刷新令牌摘要记录。
     *
     * @param token 刷新令牌领域快照
     * @param createdAt 创建时间
     */
    @Override
    public void saveToken(StoredRefreshToken token, Instant createdAt) {
        tokenRepository.save(new RefreshTokenEntity(
                token.id(),
                token.familyId(),
                token.userId(),
                token.tokenHash(),
                token.status(),
                token.expiresAt(),
                createdAt));
    }

    /**
     * 按令牌摘要加锁查询，串行化同一令牌的轮换请求。
     *
     * @param tokenHash 刷新令牌 SHA-256 摘要
     * @return 不含明文的刷新令牌快照，不存在时为空
     */
    @Override
    public Optional<StoredRefreshToken> findByTokenHashForUpdate(byte[] tokenHash) {
        return tokenRepository.findByTokenHash(tokenHash).map(this::toDomain);
    }

    /**
     * 将当前刷新令牌标记为已轮换。
     *
     * @param tokenId 令牌 UUID
     * @param rotatedAt 轮换时间
     * @throws IllegalStateException 当令牌记录不存在时抛出
     */
    @Override
    public void markRotated(UUID tokenId, Instant rotatedAt) {
        RefreshTokenEntity token = tokenRepository.findById(tokenId)
                .orElseThrow(() -> new IllegalStateException("刷新令牌记录不存在"));
        token.markRotated(rotatedAt);
    }

    /**
     * 撤销 token family 及其全部刷新令牌。
     *
     * @param familyId family UUID
     * @param revokedAt 撤销时间
     * @throws IllegalStateException 当 token family 不存在时抛出
     */
    @Override
    public void revokeFamily(UUID familyId, Instant revokedAt) {
        TokenFamilyEntity family = familyRepository.findById(familyId)
                .orElseThrow(() -> new IllegalStateException("token family 不存在"));
        family.revoke(revokedAt);
        tokenRepository.findByFamilyId(familyId).forEach(token -> token.revoke(revokedAt));
    }

    private StoredRefreshToken toDomain(RefreshTokenEntity entity) {
        TokenFamilyEntity family = familyRepository.findById(entity.getFamilyId())
                .orElseThrow(() -> new IllegalStateException("token family 不存在"));
        return new StoredRefreshToken(
                entity.getId(),
                entity.getFamilyId(),
                entity.getUserId(),
                family.getDevicePublicKeySha256() == null
                        ? null : family.getDevicePublicKeySha256().clone(),
                entity.getTokenHash().clone(),
                entity.getStatus(),
                entity.getExpiresAt());
    }
}
