package com.aifriend.feature.voice.safety

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aifriend.core.design.AiFriendTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 安全指令授权与最终明确确认 Compose 测试。
 *
 * @author codex
 * @since 2026-08-13
 */
class SafetyCommandEnrollmentScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun consentIsSeparateFromRecordingAndSubmission() {
        var grants = 0
        composeRule.setContent {
            AiFriendTheme {
                SafetyCommandEnrollmentScreen(
                    state = SafetyCommandEnrollmentUiState(
                        stage = SafetyCommandEnrollmentStage.CONSENT_REQUIRED,
                    ),
                    onBack = {},
                    onGrantConsent = { grants++ },
                    onStartFullReplacement = {},
                    onSelectCurrentPhrase = {},
                    onRequestRecording = {},
                    onFinishRecording = {},
                    onPlay = {},
                    onRetake = {},
                    onConfirmCurrentCommand = {},
                    onRedoCommand = {},
                    onConfirmAndSubmitAll = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithText("不会用于判断是谁在说话", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("同意保存个人语音模板").performClick()
        composeRule.runOnIdle { assertEquals(1, grants) }
    }

    @Test
    fun allReviewRequiresExplicitRegistrationAction() {
        var submits = 0
        val commands = SafetyCommandDefinition.entries.map {
            SafetyCommandProgress(
                definition = it,
                firstDurationMs = 1_200,
                secondDurationMs = 1_300,
                reviewed = true,
            )
        }
        composeRule.setContent {
            AiFriendTheme {
                SafetyCommandEnrollmentScreen(
                    state = SafetyCommandEnrollmentUiState(
                        stage = SafetyCommandEnrollmentStage.REVIEW_ALL,
                        commands = commands,
                    ),
                    onBack = {},
                    onGrantConsent = {},
                    onStartFullReplacement = {},
                    onSelectCurrentPhrase = {},
                    onRequestRecording = {},
                    onFinishRecording = {},
                    onPlay = {},
                    onRetake = {},
                    onConfirmCurrentCommand = {},
                    onRedoCommand = {},
                    onConfirmAndSubmitAll = { submits++ },
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithText("确认发送").assertIsDisplayed()
        composeRule.onNodeWithText("确认注册四类安全指令")
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, submits) }
    }

    @Test
    fun existingCommandsRequireExplicitFullReplacementAction() {
        var replacements = 0
        composeRule.setContent {
            AiFriendTheme {
                SafetyCommandEnrollmentScreen(
                    state = SafetyCommandEnrollmentUiState(
                        stage = SafetyCommandEnrollmentStage.EXISTING_COMPLETE,
                    ),
                    onBack = {},
                    onGrantConsent = {},
                    onStartFullReplacement = { replacements++ },
                    onSelectCurrentPhrase = {},
                    onRequestRecording = {},
                    onFinishRecording = {},
                    onPlay = {},
                    onRetake = {},
                    onConfirmCurrentCommand = {},
                    onRedoCommand = {},
                    onConfirmAndSubmitAll = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithText("本机已有完整的四类安全指令", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("重新录制全部四类").performClick()
        composeRule.runOnIdle { assertEquals(1, replacements) }
    }

    @Test
    fun firstTakeOffersTwoPresetPhrasesAndReportsSelection() {
        var selectedIndex = -1
        composeRule.setContent {
            AiFriendTheme {
                SafetyCommandEnrollmentScreen(
                    state = SafetyCommandEnrollmentUiState(
                        stage = SafetyCommandEnrollmentStage.READY_FIRST,
                    ),
                    onBack = {},
                    onGrantConsent = {},
                    onStartFullReplacement = {},
                    onSelectCurrentPhrase = { selectedIndex = it },
                    onRequestRecording = {},
                    onFinishRecording = {},
                    onPlay = {},
                    onRetake = {},
                    onConfirmCurrentCommand = {},
                    onRedoCommand = {},
                    onConfirmAndSubmitAll = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithText("发送消息").assertIsDisplayed()
        composeRule.onNodeWithText("把消息发出去").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, selectedIndex) }
    }
}
