package com.aifriend.shared.security;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 身份令牌与敏感标识保护配置。
 *
 * <p>三个密钥配置只接受环境或密钥管理系统注入；非生产环境留空时生成进程级临时密钥。
 *
 * @param issuer JWT 签发方
 * @param audience JWT 接收方
 * @param accessTokenTtl 访问令牌有效期
 * @param refreshTokenTtl 刷新令牌有效期
 * @param jwtSigningKeyBase64 JWT HMAC 密钥
 * @param dataEncryptionKeyBase64 敏感字段 AES 密钥
 * @param subjectHmacKeyBase64 微信主体查询 HMAC 密钥
 * @author Codex
 * @since 1.0.0
 */
@Validated
@ConfigurationProperties(prefix = "ai-friend.security")
public record IdentitySecurityProperties(
        @NotBlank String issuer,
        @NotBlank String audience,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl,
        String jwtSigningKeyBase64,
        String dataEncryptionKeyBase64,
        String subjectHmacKeyBase64) {
}
