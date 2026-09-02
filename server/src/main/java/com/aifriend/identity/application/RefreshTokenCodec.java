package com.aifriend.identity.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.aifriend.identity.domain.RefreshTokenStatus;
import com.aifriend.identity.domain.StoredRefreshToken;
import com.aifriend.shared.security.DigestService;

/**
 * 256 位刷新令牌生成与摘要计算器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class RefreshTokenCodec {

    private static final int TOKEN_BYTES = 32;

    private final DigestService digestService;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 创建刷新令牌编码器。
     *
     * @param digestService SHA-256 服务
     */
    public RefreshTokenCodec(DigestService digestService) {
        this.digestService = digestService;
    }

    /**
     * 生成新刷新令牌。
     *
     * @param familyId token family UUID
     * @param userId 用户 UUID
     * @param expiresAt 过期时间
     * @return 明文令牌与摘要记录
     */
    public GeneratedRefreshToken generate(UUID familyId, UUID userId, Instant expiresAt) {
        byte[] randomBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        String value = "rt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        StoredRefreshToken storedToken = new StoredRefreshToken(
                UUID.randomUUID(),
                familyId,
                userId,
                null,
                digest(value),
                RefreshTokenStatus.ACTIVE,
                expiresAt);
        return new GeneratedRefreshToken(value, storedToken);
    }

    /**
     * 计算刷新令牌摘要。
     *
     * @param token 刷新令牌明文
     * @return SHA-256 摘要
     */
    public byte[] digest(String token) {
        return digestService.sha256(token);
    }
}
