package com.aifriend.invitation.api;

import java.time.Instant;

/**
 * 创建邀请成功响应。
 *
 * @param invitationId 无权限公开邀请编号
 * @param shareUrl 含 fragment proof 的敏感分享地址，仅可交给当前用户会话
 * @param expiresAt 邀请过期时间，UTC
 * @param status OpenAPI 固定状态 WAITING
 * @author Codex
 * @since 1.0.0
 */
public record InvitationResp(
        String invitationId,
        String shareUrl,
        Instant expiresAt,
        String status) {
}
