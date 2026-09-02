package com.aifriend.feature.task

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aifriend.contract.model.AllowedAction
import com.aifriend.contract.model.TaskSession
import com.aifriend.contract.model.TaskState
import com.aifriend.core.design.AiFriendTheme
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 任务页立即取消和守护显式恢复入口测试。 */
class TaskScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activeTaskExposesImmediateLocalCancellation() {
        var cancellations = 0
        setTaskContent(
            state = TaskUiState(stage = TaskStage.ACTIVE),
            onCancelTask = { cancellations++ },
        )

        composeRule.onNodeWithText("立即取消本次任务").performClick()

        composeRule.runOnIdle { assertEquals(1, cancellations) }
    }

    @Test
    fun guardianOriginTerminalTaskRequiresExplicitResumeClick() {
        var resumes = 0
        setTaskContent(
            state = TaskUiState(
                stage = TaskStage.CANCELLED,
                guardianResumeAvailable = true,
                statusMessage = "已在本机停止本次任务",
            ),
            onResumeGuardian = { resumes++ },
        )

        composeRule.onNodeWithText("返回并恢复小友守护").assertIsDisplayed().performClick()

        composeRule.runOnIdle { assertEquals(1, resumes) }
    }

    @Test
    fun missingMessageContentExposesTargetedRepeatAction() {
        var repeats = 0
        setTaskContent(
            state = TaskUiState(
                stage = TaskStage.ACTIVE,
                session = repeatSession(TaskState.NEEDS_CONTENT_REPEAT),
                statusMessage = "请重新说要告诉亲友的内容",
            ),
            onRepeatTask = { repeats++ },
        )

        composeRule.onNodeWithText("请重新说要告诉亲友的内容").assertIsDisplayed()
        composeRule.onNodeWithText("重新说消息内容").assertIsDisplayed().performClick()

        composeRule.runOnIdle { assertEquals(1, repeats) }
    }

    @Test
    fun rehearsalInProgressHidesConfirmationActions() {
        setTaskContent(
            state = TaskUiState(
                stage = TaskStage.REHEARSING,
                session = confirmationSession(),
                statusMessage = "正在播放完整复述",
            ),
        )

        composeRule.onAllNodesWithText("说发送确认指令").assertCountEquals(0)
        composeRule.onNodeWithText("请先听完，播放完成前不能确认").assertIsDisplayed()
    }

    @Test
    fun rehearsalFailureOffersReplayAndFreshRecording() {
        var replays = 0
        var repeats = 0
        setTaskContent(
            state = TaskUiState(
                stage = TaskStage.REHEARSAL_FAILED,
                session = confirmationSession(),
                statusMessage = "完整复述没有播放完",
            ),
            onRetryRehearsal = { replays++ },
            onRepeatTask = { repeats++ },
        )

        composeRule.onNodeWithText("重新播放完整复述").performClick()
        composeRule.onNodeWithText("重新说完整任务").performClick()

        composeRule.runOnIdle {
            assertEquals(1, replays)
            assertEquals(1, repeats)
        }
    }

    @Test
    fun taskStatusExposesNonColorBadgeAndAccessibleLiveMessage() {
        setTaskContent(
            state = TaskUiState(
                stage = TaskStage.ACTIVE,
                session = confirmationSession(),
                statusMessage = "完整复述已播放",
            ),
        )

        composeRule.onNodeWithText("请说安全指令").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "任务状态，完整复述已结束，请说对应的个人安全指令。完整复述已播放",
        ).assertIsDisplayed()
    }

    @Test
    fun completedRehearsalOffersExplicitLocalReplay() {
        var replays = 0
        setTaskContent(
            state = TaskUiState(
                stage = TaskStage.ACTIVE,
                session = confirmationSession(),
                statusMessage = "完整复述已播放",
            ),
            onReplaySummary = { replays++ },
        )

        composeRule.onNodeWithText("再听一遍完整复述").assertIsDisplayed().performClick()

        composeRule.runOnIdle { assertEquals(1, replays) }
    }

    @Test
    fun verifiedMessageResultShowsLargeTimedContinueAction() {
        var continuations = 0
        setTaskContent(
            state = TaskUiState(
                stage = TaskStage.COMPLETED,
                continuation = MessageContinuationUi("李明", 5),
                statusMessage = "已发送；5 秒内可继续给李明说话",
            ),
            onContinueMessage = { continuations++ },
        )

        composeRule.onNodeWithText("✉ 继续给李明说话（5秒）").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("每条新消息都要重新录音、听完整复述并再次确认").assertIsDisplayed()

        composeRule.runOnIdle { assertEquals(1, continuations) }
    }

    @Test
    fun missingTaskAudioConsentShowsPurposeBeforeExplicitGrant() {
        var grants = 0
        setTaskContent(
            state = TaskUiState(
                stage = TaskStage.CONSENT_REQUIRED,
                statusMessage = "请先确认是否允许处理当前任务语音",
            ),
            onGrantTaskAudioConsent = { grants++ },
        )

        composeRule.onNodeWithText("任务语音不会用于通用模型训练；不同意时不会录音、上传或执行任何微信操作，本页面也不会替您自动同意。")
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("开始说话").assertCountEquals(0)
        composeRule.onNodeWithText("同意处理任务语音").assertIsDisplayed().performClick()

        composeRule.runOnIdle { assertEquals(1, grants) }
    }

    private fun setTaskContent(
        state: TaskUiState,
        onCancelTask: () -> Unit = {},
        onRepeatTask: () -> Unit = {},
        onRetryRehearsal: () -> Unit = {},
        onReplaySummary: () -> Unit = {},
        onResumeGuardian: () -> Unit = {},
        onContinueMessage: () -> Unit = {},
        onGrantTaskAudioConsent: () -> Unit = {},
    ) {
        composeRule.setContent {
            AiFriendTheme {
                TaskScreen(
                    state = state,
                    onStartTask = {},
                    onGrantTaskAudioConsent = onGrantTaskAudioConsent,
                    onStopTask = {},
                    onSelectCandidate = {},
                    onStartConfirmation = {},
                    onStopConfirmation = {},
                    onCancelTask = onCancelTask,
                    onRepeatTask = onRepeatTask,
                    onRetryRehearsal = onRetryRehearsal,
                    onReplaySummary = onReplaySummary,
                    onRetry = {},
                    onContinueMessage = onContinueMessage,
                    onResumeGuardian = onResumeGuardian,
                    onBack = {},
                )
            }
        }
    }

    private fun repeatSession(state: TaskState) = TaskSession(
        sessionId = "ts_test",
        sessionVersion = 1,
        state = state,
        candidates = emptyList(),
        allowedActions = setOf(AllowedAction.RETRY, AllowedAction.CANCEL),
        expiresAt = OffsetDateTime.now().plusMinutes(5),
    )

    private fun confirmationSession() = TaskSession(
        sessionId = "ts_confirmation",
        sessionVersion = 1,
        state = TaskState.AWAITING_CONFIRMATION,
        candidates = emptyList(),
        allowedActions = setOf(AllowedAction.CONFIRM_SEND, AllowedAction.CANCEL),
        expiresAt = OffsetDateTime.now().plusMinutes(5),
        summaryHash = "summary-hash",
    )
}
