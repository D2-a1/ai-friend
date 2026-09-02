package com.aifriend.identity.application;

import java.time.Instant;

/**
 * 短期访问令牌签发结果。
 *
 * @param value JWT 明文，仅返回客户端且禁止记录日志
 * @param expiresAt 过期时间
 * @author Codex
 * @since 1.0.0
 */
public record AccessToken(String value, Instant expiresAt) {
}
