package com.aifriend.feature.contact.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aifriend.contract.model.Contact
import com.aifriend.contract.model.ContactStatus
import com.aifriend.core.design.AiFriendTheme
import com.aifriend.feature.wechat.WechatLocalVerificationCaptureState
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ContactManagementScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unbindActionRequiresTwoVisibleConfirmations() {
        val contact = contact()
        var state by mutableStateOf(ContactManagementUiState(contacts = listOf(contact)))
        var confirmCount = 0
        composeRule.setContent {
            AiFriendTheme {
                ContactManagementScreen(
                    state = state,
                    onBack = {},
                    onRefresh = {},
                    onManageAliases = {},
                    onRequestUnbind = {
                        state = state.copy(
                            pendingUnbind = PendingContactUnbind(
                                contactId = contact.id,
                                contactLabel = "二女儿",
                                expectedContactVersion = contact.version,
                                stage = ContactUnbindConfirmationStage.WARNING,
                            ),
                        )
                    },
                    onContinueUnbind = {
                        state = state.copy(
                            pendingUnbind = state.pendingUnbind?.copy(
                                stage = ContactUnbindConfirmationStage.FINAL_CONFIRMATION,
                            ),
                        )
                    },
                    onConfirmUnbind = { confirmCount++ },
                    onCancelUnbind = { state = state.copy(pendingUnbind = null) },
                    onDismissMessage = {},
                )
            }
        }

        composeRule.onNodeWithText("解除绑定").performClick()
        composeRule.onNodeWithText("要解除绑定吗？").assertIsDisplayed()
        composeRule.onNodeWithText("继续").performClick()
        composeRule.onNodeWithText("再次确认解除绑定").assertIsDisplayed()
        composeRule.onNodeWithText("确认解除绑定").performClick()

        composeRule.runOnIdle { assertEquals(1, confirmCount) }
    }

    @Test
    fun verifiedContactExposesAliasEnrollmentEntry() {
        val contact = contact()
        var selectedContactId: String? = null
        composeRule.setContent {
            AiFriendTheme {
                ContactManagementScreen(
                    state = ContactManagementUiState(contacts = listOf(contact)),
                    onBack = {},
                    onRefresh = {},
                    onManageAliases = { selectedContactId = it.id },
                    onRequestUnbind = {},
                    onContinueUnbind = {},
                    onConfirmUnbind = {},
                    onCancelUnbind = {},
                    onDismissMessage = {},
                )
            }
        }

        composeRule.onNodeWithText("设置称呼").performClick()

        composeRule.runOnIdle { assertEquals(contact.id, selectedContactId) }
    }

    @Test
    fun debugDemoContactDoesNotExposeUnbindAction() {
        val demoContact = contact().copy(relationship = "仅用于本机体验")
        composeRule.setContent {
            AiFriendTheme {
                ContactManagementScreen(
                    state = ContactManagementUiState(contacts = listOf(demoContact)),
                    onBack = {},
                    onRefresh = {},
                    onManageAliases = {},
                    onRequestUnbind = {},
                    onContinueUnbind = {},
                    onConfirmUnbind = {},
                    onCancelUnbind = {},
                    onDismissMessage = {},
                    demoEnabled = true,
                    onPrepareDemoContact = {},
                    onOpenSafetyCommands = {},
                    onStartTask = {},
                )
            }
        }

        composeRule.onNodeWithText("先设置体验称呼").assertExists()
        composeRule.onAllNodesWithText("解除绑定").assertCountEquals(0)
    }

    @Test
    fun pendingContactOpensThreeStepLocalVerificationPage() {
        val pendingContact = contact().copy(status = ContactStatus.PENDING_LOCAL_VERIFY)
        var state by mutableStateOf(ContactManagementUiState(contacts = listOf(pendingContact)))
        composeRule.setContent {
            AiFriendTheme {
                ContactManagementScreen(
                    state = state,
                    onBack = {},
                    onRefresh = {},
                    onManageAliases = {},
                    onRequestUnbind = {},
                    onContinueUnbind = {},
                    onConfirmUnbind = {},
                    onCancelUnbind = {},
                    onDismissMessage = {},
                    onStartLocalVerification = {
                        state = state.copy(
                            localVerification = PendingContactLocalVerification(
                                contactId = pendingContact.id,
                                contactLabel = "二女儿",
                                sourceContactVersion = pendingContact.version,
                                capture = WechatLocalVerificationCaptureState(
                                    active = true,
                                    message = "请开始验证",
                                ),
                            ),
                        )
                    },
                )
            }
        }

        composeRule.onNodeWithText("完成本机验证").performClick()

        composeRule.onNodeWithText("本机验证").assertIsDisplayed()
        composeRule.onNodeWithText("已完成 0 / 1 次").assertIsDisplayed()
        composeRule.onNodeWithText("微信辅助服务：未连接").assertIsDisplayed()
        composeRule.onNodeWithText("请先开启微信辅助服务")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onNodeWithText("检查微信辅助服务")
            .performScrollTo()
            .assertIsDisplayed()

        state = state.copy(
            localVerification = state.localVerification?.copy(
                capture = WechatLocalVerificationCaptureState(
                    accessibilityReady = true,
                    active = true,
                    message = "可以开始验证",
                ),
            ),
        )

        composeRule.onNodeWithText("微信辅助服务：已连接").assertIsDisplayed()
        composeRule.onNodeWithText("打开微信完成第 1 次")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    private fun contact(): Contact {
        val now = OffsetDateTime.parse("2026-08-09T08:00:00Z")
        return Contact(
            id = "ct_0123456789abcdef0123456789abcdef",
            status = ContactStatus.ACTIVE_NO_ALIAS,
            aliasCount = 0,
            version = 3,
            createdAt = now.minusDays(1),
            updatedAt = now,
            remark = "二女儿",
            aliases = emptyList(),
        )
    }
}
