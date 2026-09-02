package com.aifriend.invitation.api;

import java.time.Instant;

/**
 * 进程恢复后可读取的待处理邀请响应。
 *
 * @param invitationId 无权限公开邀请编号
 * @param expiresAt 固定过期时间
 * @param status 对外统一状态 WAITING_CONFIRMATION
 * @author Codex
 * @since 1.0.0
 */
public record PendingInvitationResp(
        String invitationId,
        Instant expiresAt,
        String status) {
}
