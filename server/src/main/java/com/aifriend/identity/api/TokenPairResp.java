package com.aifriend.identity.api;

import java.time.Instant;

/**
 * 访问令牌与轮换刷新令牌响应。
 *
 * @param accessToken 短期访问令牌
 * @param accessTokenExpiresAt 访问令牌过期时间
 * @param refreshToken 刷新令牌
 * @param refreshTokenExpiresAt 刷新令牌过期时间
 * @param user 当前用户
 * @author Codex
 * @since 1.0.0
 */
public record TokenPairResp(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        CurrentUserResp user) {
}
