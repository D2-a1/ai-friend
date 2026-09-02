package com.aifriend.invitation.application;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 亲友邀请链接与有效期配置。
 *
 * @param baseUrl 同源 HTTPS 邀请页基础地址
 * @param ttl 邀请固定有效期
 * @author Codex
 * @since 1.0.0
 */
@Validated
@ConfigurationProperties(prefix = "ai-friend.invitation")
public record InvitationProperties(@NotNull URI baseUrl, @NotNull Duration ttl) {

    /**
     * 校验邀请链接的固定安全边界。
     *
     * @throws NullPointerException 当基础地址或有效期为空时抛出
     * @throws IllegalArgumentException 当基础地址不安全或有效期不是 24 小时时抛出
     */
    public InvitationProperties {
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        if (!"https".equalsIgnoreCase(baseUrl.getScheme())
                || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException("邀请基础地址必须是无用户信息、查询和 fragment 的 HTTPS 地址");
        }
        if (!Duration.ofHours(24).equals(ttl)) {
            throw new IllegalArgumentException("邀请有效期必须固定为 24 小时");
        }
    }
}
