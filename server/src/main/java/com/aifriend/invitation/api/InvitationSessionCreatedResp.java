package com.aifriend.invitation.api;

import java.net.URI;
import java.time.Instant;

/**
 * 受限邀请会话创建响应。
 *
 * @param expiresAt 固定 30 分钟过期时间
 * @param csrfToken 同源邀请页后续写操作使用的一次性会话令牌
 * @param wechatAuthorizationUrl 含一次性 OAuth state 的授权入口
 * @author Codex
 * @since 1.0.0
 */
public record InvitationSessionCreatedResp(
        Instant expiresAt,
        String csrfToken,
        URI wechatAuthorizationUrl) {
}
