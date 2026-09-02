package com.aifriend.shared.security;

import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 从已验证 JWT 派生的当前用户。
 *
 * @param id 内部用户 UUID
 * @param publicId 对外用户编号
 * @author Codex
 * @since 1.0.0
 */
public record CurrentUser(UUID id, String publicId) {

    /**
     * 从已验证 JWT 构建当前用户。
     *
     * @param jwt 已验证 JWT
     * @return 当前用户
     */
    public static CurrentUser from(Jwt jwt) {
        String publicId = jwt.getSubject();
        return new CurrentUser(PublicIdCodec.parseUserId(publicId), publicId);
    }
}
