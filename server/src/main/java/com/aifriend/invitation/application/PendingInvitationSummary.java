package com.aifriend.invitation.application;

import java.time.Instant;

/**
 * 可在客户端进程恢复后重新读取的待处理邀请摘要。
 *
 * <p>摘要不包含 proof、分享地址或亲友操作进度，只用于展示和撤销。
 *
 * @param invitationId 无权限公开邀请编号
 * @param expiresAt 固定过期时间
 * @author Codex
 * @since 1.0.0
 */
public record PendingInvitationSummary(String invitationId, Instant expiresAt) {
}
