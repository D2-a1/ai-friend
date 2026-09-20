package com.aifriend.feature.contact.alias

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.closeSoftKeyboard
import com.aifriend.core.design.AiFriendTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 称呼双录 Compose 交互测试。
 *
 * @author codex
 * @since 2026-08-12
 */
class AliasEnrollmentScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun consentIsRequiredBeforeAnyAliasRecordingActionIsShown() {
        val state = AliasEnrollmentUiState(
            contactId = "ct_1",
            contactLabel = "当前已绑定亲友",
            stage = AliasEnrollmentStage.CONSENT_REQUIRED,
        )
        var grantCount = 0
        composeRule.setContent {
            AiFriendTheme {
                AliasEnrollmentScreen(
                    state = state,
                    onBack = {},
                    onDisplayTextChanged = {},
                    onGrantConsent = { grantCount++ },
                    onRequestRecording = {},
                    onFinishRecording = {},
                    onPlay = {},
                    onRetake = {},
                    onConfirmAndSubmit = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onAllNodesWithText("开始录第一遍").assertCountEquals(0)
        composeRule.onNodeWithText("同意保存个人语音模板")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, grantCount) }
    }

    @Test
    fun reviewRequiresDisplayTextAndExplicitSaveAction() {
        var state by mutableStateOf(
            AliasEnrollmentUiState(
                contactId = "ct_1",
                contactLabel = "二女儿",
                stage = AliasEnrollmentStage.REVIEW,
                firstDurationMs = 1_200,
                secondDurationMs = 1_300,
            ),
        )
        var saveCount = 0
        composeRule.setContent {
            AiFriendTheme {
                AliasEnrollmentScreen(
                    state = state,
                    onBack = {},
                    onDisplayTextChanged = { state = state.copy(displayText = it) },
                    onRequestRecording = {},
                    onFinishRecording = {},
                    onPlay = {},
                    onRetake = {},
                    onConfirmAndSubmit = { saveCount++ },
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithText("确认保存称呼").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("展示称呼输入框").performTextInput("二女儿")
        closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("确认保存称呼")
            .assertIsEnabled()
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle { assertEquals(1, saveCount) }
    }

    @Test
    fun firstRecordingMustBeCompletedBeforeSecondRecordingActionAppears() {
        val state = AliasEnrollmentUiState(
            contactId = "ct_1",
            contactLabel = "二女儿",
            stage = AliasEnrollmentStage.FIRST_RECORDED,
            firstDurationMs = 1_200,
        )
        var secondRecordingCount = 0
        composeRule.setContent {
            AiFriendTheme {
                AliasEnrollmentScreen(
                    state = state,
                    onBack = {},
                    onDisplayTextChanged = {},
                    onRequestRecording = { secondRecordingCount++ },
                    onFinishRecording = {},
                    onPlay = {},
                    onRetake = {},
                    onConfirmAndSubmit = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithText("第一遍：1.2 秒").assertIsDisplayed()
        composeRule.onNodeWithText("开始录第二遍").performClick()

        composeRule.runOnIdle { assertEquals(1, secondRecordingCount) }
    }

    @Test
    fun existingAliasRequiresSecondConfirmationBeforeDeletion() {
        val alias = AliasSummaryUiState("al_1", "二女儿")
        var state by mutableStateOf(
            AliasEnrollmentUiState(
                contactId = "ct_1",
                contactLabel = "二女儿",
                existingAliases = listOf(alias),
                stage = AliasEnrollmentStage.READY_FIRST,
            ),
        )
        var confirmationCount = 0
        composeRule.setContent {
            AiFriendTheme {
                AliasEnrollmentScreen(
                    state = state,
                    onBack = {},
                    onDisplayTextChanged = {},
                    onRequestRecording = {},
                    onFinishRecording = {},
                    onPlay = {},
                    onRetake = {},
                    onConfirmAndSubmit = {},
                    onRequestAliasDeletion = { state = state.copy(pendingAliasDeletion = alias) },
                    onCancelAliasDeletion = { state = state.copy(pendingAliasDeletion = null) },
                    onConfirmAliasDeletion = { confirmationCount++ },
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithText("删除这个称呼").performScrollTo().performClick()
        composeRule.onNodeWithText("确认删除称呼").assertIsDisplayed()
        composeRule.onNodeWithText("确认删除").performClick()

        composeRule.runOnIdle { assertEquals(1, confirmationCount) }
    }

    @Test
    fun incompatibleAliasExplainsThatOnlyTheAliasMustBeRecordedAgain() {
        val state = AliasEnrollmentUiState(
            contactId = "ct_1",
            contactLabel = "老大",
            existingAliases = listOf(
                AliasSummaryUiState("al_1", "老大", compatible = false),
            ),
            stage = AliasEnrollmentStage.READY_FIRST,
        )
        composeRule.setContent {
            AiFriendTheme {
                AliasEnrollmentScreen(
                    state = state,
                    onBack = {},
                    onDisplayTextChanged = {},
                    onRequestRecording = {},
                    onFinishRecording = {},
                    onPlay = {},
                    onRetake = {},
                    onConfirmAndSubmit = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithText(
            "这个称呼由旧版本录制，当前不能用于联系亲友。请删除后只重新录制这个称呼；安全指令无需重录。",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun completedAliasOffersExplicitContinueActionWhenCapacityRemains() {
        val state = AliasEnrollmentUiState(
            contactId = "ct_1",
            contactLabel = "二女儿",
            existingAliases = listOf(AliasSummaryUiState("al_1", "二女儿")),
            stage = AliasEnrollmentStage.COMPLETED,
            completedAliasText = "二女儿",
            canAddAnotherAlias = true,
        )
        var continueCount = 0
        composeRule.setContent {
            AiFriendTheme {
                AliasEnrollmentScreen(
                    state = state,
                    onBack = {},
                    onDisplayTextChanged = {},
                    onRequestRecording = {},
                    onFinishRecording = {},
                    onPlay = {},
                    onRetake = {},
                    onConfirmAndSubmit = {},
                    onContinueAfterCompletion = { continueCount++ },
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithText("继续添加称呼").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, continueCount) }
    }
}
