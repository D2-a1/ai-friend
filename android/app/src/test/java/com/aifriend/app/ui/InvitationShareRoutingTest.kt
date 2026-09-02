package com.aifriend.app.ui

import com.aifriend.feature.invitation.PendingInvitation
import java.time.Instant
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 亲友邀请分享前的到期门禁测试。 */
class InvitationShareRoutingTest {

    @Test
    fun validInvitationIsSharedWithoutRefresh() {
        var sharedUrl: String? = null
        var refreshCount = 0

        routeInvitationShare(
            invitation = invitation(
                shareUrl = "https://example.invalid/invite#proof",
                expiresAt = "2026-08-30T01:00:00Z",
            ),
            now = Instant.parse("2026-08-30T00:00:00Z"),
            onShare = { sharedUrl = it },
            onRefresh = { refreshCount++ },
        )

        assertEquals("https://example.invalid/invite#proof", sharedUrl)
        assertEquals(0, refreshCount)
    }

    @Test
    fun expiredOrExactlyDueInvitationRefreshesWithoutSharing() {
        listOf(
            "2026-08-29T23:59:59Z",
            "2026-08-30T00:00:00Z",
        ).forEach { expiresAt ->
            var sharedUrl: String? = null
            var refreshCount = 0

            routeInvitationShare(
                invitation = invitation(
                    shareUrl = "https://example.invalid/invite#proof",
                    expiresAt = expiresAt,
                ),
                now = Instant.parse("2026-08-30T00:00:00Z"),
                onShare = { sharedUrl = it },
                onRefresh = { refreshCount++ },
            )

            assertNull(sharedUrl)
            assertEquals(1, refreshCount)
        }
    }

    @Test
    fun missingShareAddressRefreshesWithoutSharing() {
        var sharedUrl: String? = null
        var refreshCount = 0

        routeInvitationShare(
            invitation = invitation(
                shareUrl = "  ",
                expiresAt = "2026-08-30T01:00:00Z",
            ),
            now = Instant.parse("2026-08-30T00:00:00Z"),
            onShare = { sharedUrl = it },
            onRefresh = { refreshCount++ },
        )

        assertNull(sharedUrl)
        assertEquals(1, refreshCount)
    }

    @Test
    fun validAddressIsTargetedOnlyToWechat() {
        var received: WechatInvitationShareRequest? = null
        var failed = false

        routeWechatInvitationShare(
            shareUrl = "https://example.invalid/invite#proof",
            launchWechat = {
                received = it
                true
            },
            onFailure = { failed = true },
        )

        assertEquals(WECHAT_PACKAGE_NAME, received?.packageName)
        assertEquals("text/plain", received?.mimeType)
        assertEquals("https://example.invalid/invite#proof", received?.text)
        assertFalse(failed)
    }

    @Test
    fun missingWechatReportsFailureWithoutRetryingLaunch() {
        var launchCount = 0
        var failureCount = 0

        routeWechatInvitationShare(
            shareUrl = "https://example.invalid/invite#proof",
            launchWechat = {
                launchCount++
                false
            },
            onFailure = { failureCount++ },
        )

        assertEquals(1, launchCount)
        assertEquals(1, failureCount)
    }

    @Test
    fun wechatLaunchExceptionReportsFailureWithoutEscaping() {
        var failureCount = 0

        routeWechatInvitationShare(
            shareUrl = "https://example.invalid/invite#proof",
            launchWechat = { error("微信启动异常") },
            onFailure = { failureCount++ },
        )

        assertEquals(1, failureCount)
    }

    @Test
    fun shareFailureKeepsActiveInvitationForRetryOrRevocation() {
        val invitation = invitation(
            shareUrl = "https://example.invalid/invite#proof",
            expiresAt = "2026-08-30T01:00:00Z",
        )

        val failed = InvitationUiState.Active(invitation).withWechatShareFailure()

        assertTrue(failed is InvitationUiState.Error)
        failed as InvitationUiState.Error
        assertEquals(WECHAT_SHARE_FAILURE_MESSAGE, failed.message)
        assertSame(invitation, failed.invitation)
    }

    @Test
    fun unrelatedInvitationStateIsNotChangedByShareFailure() {
        val idle = InvitationUiState.Idle

        val unchanged = idle.withWechatShareFailure()

        assertSame(idle, unchanged)
    }

    private fun invitation(
        shareUrl: String,
        expiresAt: String,
    ): PendingInvitation = PendingInvitation(
        invitationId = "invitation-one",
        shareUrl = shareUrl,
        expiresAt = OffsetDateTime.parse(expiresAt),
    )
}
