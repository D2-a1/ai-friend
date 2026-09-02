package com.aifriend.identity.application;

import java.time.Instant;

import com.aifriend.identity.domain.UserAccount;

/**
 * 登录或轮换返回的令牌对。
 *
 * @param accessToken 访问令牌
 * @param accessTokenExpiresAt 访问令牌过期时间
 * @param refreshToken 刷新令牌
 * @param refreshTokenExpiresAt 刷新令牌过期时间
 * @param user 用户账号
 * @author Codex
 * @since 1.0.0
 */
public record TokenPairResult(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        UserAccount user) {
}
