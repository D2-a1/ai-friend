package com.aifriend.app.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aifriend.core.design.AiFriendTheme
import com.aifriend.feature.invitation.PendingInvitation
import com.aifriend.feature.invitation.RestoredInvitation
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 家人当面协助边界、邀请状态和现有功能跳转测试。 */
class FamilySetupScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun localOnlyBoundaryAndExistingSetupLinksAreExplicit() {
        var contacts = 0
        var commands = 0
        var settings = 0
        var back = 0
        setContent(
            onOpenContacts = { contacts++ },
            onOpenSafetyCommands = { commands++ },
            onOpenSettings = { settings++ },
            onBack = { back++ },
        )

        composeRule.onNodeWithText(
            "请家人在老人这部手机上操作。本应用不提供远程控制、家属管理员或代替老人确认。",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("打开我的亲友").performScrollTo().performClick()
        composeRule.onNodeWithText("打开安全指令录制").performScrollTo().performClick()
        composeRule.onNodeWithText("打开设置与帮助").performScrollTo().performClick()
        composeRule.onNodeWithText("完成并返回首页").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(1, contacts)
            assertEquals(1, commands)
            assertEquals(1, settings)
            assertEquals(1, back)
        }
    }

    @Test
    fun activeInvitationSharesOpaqueUrlAndCanBeRevoked() {
        var sharedUrl: String? = null
        var revokedInvitationId: String? = null
        val invitation = PendingInvitation(
            invitationId = "in_test",
            shareUrl = "https://example.invalid/invite#private-proof",
            expiresAt = OffsetDateTime.parse("2099-08-23T00:00:00Z"),
        )
        setContent(
            invitationState = InvitationUiState.Active(invitation),
            onShareInvitation = { sharedUrl = it },
            onRevokeInvitation = { revokedInvitationId = it },
        )

        composeRule.onNodeWithText("邀请已创建，24 小时内有效。")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("打开微信分享邀请").performScrollTo().performClick()
        composeRule.onNodeWithText("撤销本次邀请").performScrollTo().performClick()
        composeRule.onNodeWithText("确认撤销亲友邀请？").assertIsDisplayed()
        composeRule.onAllNodesWithText(invitation.shareUrl).assertCountEquals(0)

        composeRule.runOnIdle {
            assertEquals(null, revokedInvitationId)
        }
        composeRule.onNodeWithText("确认撤销").performClick()

        composeRule.runOnIdle {
            assertEquals(invitation.shareUrl, sharedUrl)
            assertEquals(invitation.invitationId, revokedInvitationId)
        }
    }

    @Test
    fun expiredInvitationRefreshesWithoutOpeningShare() {
        var sharedUrl: String? = null
        var refreshCount = 0
        val invitation = PendingInvitation(
            invitationId = "in_expired",
            shareUrl = "https://example.invalid/invite#expired-proof",
            expiresAt = OffsetDateTime.parse("2020-01-01T00:00:00Z"),
        )
        setContent(
            invitationState = InvitationUiState.Active(invitation),
            onShareInvitation = { sharedUrl = it },
            onRefreshInvitations = { refreshCount++ },
        )

        composeRule.onNodeWithText("打开微信分享邀请").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(null, sharedUrl)
            assertEquals(1, refreshCount)
        }
    }

    @Test
    fun restoredInvitationHasNoShareActionAndCanBeRevoked() {
        var revokedInvitationId: String? = null
        val invitation = RestoredInvitation(
            invitationId = "iv_restored",
            expiresAt = OffsetDateTime.parse("2026-08-30T00:00:00Z"),
        )
        setContent(
            invitationState = InvitationUiState.Recovered(listOf(invitation)),
            onRevokeInvitation = { revokedInvitationId = it },
        )

        composeRule.onNodeWithText("等待亲友确认").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("打开微信分享邀请").assertCountEquals(0)
        composeRule.onNodeWithText("撤销待处理邀请 1").performScrollTo().performClick()
        composeRule.onNodeWithText("确认撤销亲友邀请？").assertIsDisplayed()

        composeRule.runOnIdle {
            assertEquals(null, revokedInvitationId)
        }
        composeRule.onNodeWithText("确认撤销").performClick()

        composeRule.runOnIdle {
            assertEquals(invitation.invitationId, revokedInvitationId)
        }
    }

    @Test
    fun creationFailureOffersRetryWithoutFakeShareAction() {
        var create = 0
        setContent(
            invitationState = InvitationUiState.Error("邀请创建失败，请重试。"),
            onCreateInvitation = { create++ },
        )

        composeRule.onNodeWithText("邀请创建失败，请重试。")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("重新创建邀请").performScrollTo().performClick()
        composeRule.onAllNodesWithText("打开微信分享邀请").assertCountEquals(0)
        composeRule.runOnIdle { assertEquals(1, create) }
    }

    private fun setContent(
        invitationState: InvitationUiState = InvitationUiState.Idle,
        onCreateInvitation: () -> Unit = {},
        onShareInvitation: (String) -> Unit = {},
        onRefreshInvitations: () -> Unit = {},
        onRevokeInvitation: (String) -> Unit = {},
        onOpenContacts: () -> Unit = {},
        onOpenSafetyCommands: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            AiFriendTheme {
                FamilySetupScreen(
                    invitationState = invitationState,
                    onCreateInvitation = onCreateInvitation,
                    onRefreshInvitations = onRefreshInvitations,
                    onShareInvitation = onShareInvitation,
                    onRevokeInvitation = onRevokeInvitation,
                    onOpenContacts = onOpenContacts,
                    onOpenSafetyCommands = onOpenSafetyCommands,
                    onOpenSettings = onOpenSettings,
                    onBack = onBack,
                )
            }
        }
    }
}
