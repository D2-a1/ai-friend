package com.aifriend.invitation.application;

import java.net.URI;
import java.time.Instant;

/**
 * 仅在当前响应内短暂存在的受限邀请会话凭据。
 *
 * @param sessionToken 仅写入安全 Cookie 的随机令牌
 * @param csrfToken 仅返回同源邀请页的 CSRF 随机令牌
 * @param wechatAuthorizationUrl 含一次性 OAuth state 的授权入口
 * @param expiresAt 会话固定过期时间
 * @author Codex
 * @since 1.0.0
 */
public record CreatedInvitationSession(
        String sessionToken,
        String csrfToken,
        URI wechatAuthorizationUrl,
        Instant expiresAt) {
}
