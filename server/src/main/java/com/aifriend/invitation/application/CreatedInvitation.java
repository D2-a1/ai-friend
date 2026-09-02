package com.aifriend.invitation.application;

import java.time.Instant;

/**
 * 创建邀请后仅向当前调用返回的敏感结果。
 *
 * @param invitationId 无权限公开编号
 * @param shareUrl proof 仅位于 fragment 的完整分享地址
 * @param expiresAt 过期时间
 * @author Codex
 * @since 1.0.0
 */
public record CreatedInvitation(String invitationId, String shareUrl, Instant expiresAt) {
}
