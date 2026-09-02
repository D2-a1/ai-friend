package com.aifriend.feature.invitation

import java.time.OffsetDateTime

/**
 * 从服务端恢复的待处理邀请摘要。
 *
 * 摘要不含 proof、分享地址或亲友操作进度，只能用于展示和撤销。
 *
 * @author codex
 * @since 2026-08-29
 */
data class RestoredInvitation(
    val invitationId: String,
    val expiresAt: OffsetDateTime,
)
