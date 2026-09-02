package com.aifriend.feature.invitation

/**
 * 当前用户亲友邀请创建与撤销端口。
 *
 * @author codex
 * @since 2026-08-07
 */
interface InvitationRepository {
    suspend fun listPending(): List<RestoredInvitation>

    suspend fun create(): PendingInvitation

    suspend fun revoke(invitationId: String)
}
