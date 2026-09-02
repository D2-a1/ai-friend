package com.aifriend.feature.invitation

import java.time.OffsetDateTime

/**
 * 仅保存在当前进程内存中的待分享邀请。
 *
 * 完整分享地址包含敏感 proof，不得写入日志、剪贴板、Bundle、DataStore、Room 或其他持久化介质。
 *
 * @author codex
 * @since 2026-08-07
 */
data class PendingInvitation(
    val invitationId: String,
    val shareUrl: String,
    val expiresAt: OffsetDateTime,
)
