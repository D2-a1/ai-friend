package com.aifriend.feature.invitation

import com.aifriend.contract.api.InvitationsApi
import com.aifriend.feature.auth.AuthApiException
import com.aifriend.feature.auth.AuthSessionRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response

/**
 * 使用 OpenAPI InvitationsApi 的邀请仓库；401 时只刷新并重试一次。
 *
 * @author codex
 * @since 2026-08-07
 */
@Singleton
class DefaultInvitationRepository @Inject constructor(
    private val invitationsApi: InvitationsApi,
    private val authSessionRepository: AuthSessionRepository,
) : InvitationRepository {

    override suspend fun listPending(): List<RestoredInvitation> {
        var response = invitationsApi.listContactInvitations()
        if (response.code() == 401) {
            authSessionRepository.refresh()
            response = invitationsApi.listContactInvitations()
        }
        val invitations = response.body()?.data?.takeIf { response.isSuccessful }
            ?: throw AuthApiException(response.code(), failureMessage(response.code()))
        return invitations.map { invitation ->
            RestoredInvitation(
                invitationId = invitation.invitationId,
                expiresAt = invitation.expiresAt,
            )
        }
    }

    override suspend fun create(): PendingInvitation {
        val idempotencyKey = UUID.randomUUID().toString()
        var response = invitationsApi.createContactInvitation(idempotencyKey)
        if (response.code() == 401) {
            authSessionRepository.refresh()
            response = invitationsApi.createContactInvitation(idempotencyKey)
        }
        val invitation = response.body()?.data?.takeIf { response.isSuccessful }
            ?: throw AuthApiException(response.code(), failureMessage(response.code()))
        return PendingInvitation(
            invitationId = invitation.invitationId,
            shareUrl = invitation.shareUrl.toASCIIString(),
            expiresAt = invitation.expiresAt,
        )
    }

    override suspend fun revoke(invitationId: String) {
        val idempotencyKey = UUID.randomUUID().toString()
        executeEmptyAuthenticated {
            invitationsApi.revokeContactInvitation(invitationId, idempotencyKey)
        }
    }

    private suspend fun executeEmptyAuthenticated(request: suspend () -> Response<Unit>) {
        var response = request()
        if (response.code() == 401) {
            authSessionRepository.refresh()
            response = request()
        }
        if (!response.isSuccessful) {
            throw AuthApiException(response.code(), failureMessage(response.code()))
        }
    }

    private fun failureMessage(status: Int): String = when (status) {
        404 -> "没有找到该邀请"
        409 -> "邀请状态已经变化，请刷新后重试"
        429 -> "今天创建邀请的次数已达上限"
        else -> "邀请操作失败，请稍后重试"
    }
}
