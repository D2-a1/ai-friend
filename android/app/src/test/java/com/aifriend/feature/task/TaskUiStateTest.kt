package com.aifriend.feature.task

import com.aifriend.contract.model.AllowedAction
import com.aifriend.contract.model.ConfirmationAction
import com.aifriend.contract.model.Consent
import com.aifriend.contract.model.ConsentDecision
import com.aifriend.contract.model.ConsentType
import com.aifriend.contract.model.TaskSession
import com.aifriend.contract.model.TaskState
import com.aifriend.core.feedback.FeedbackSymbol
import com.aifriend.core.feedback.HapticCue
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 任务状态老人提示映射测试。 */
class TaskUiStateTest {

    @Test
    fun everyTaskStateHasExplicitChineseUserMessage() {
        TaskState.entries.forEach { state ->
            val message = state.userMessage()

            assertTrue(message.any { it in '\u4e00'..'\u9fff' })
            assertFalse(message.any { it in 'A'..'Z' || it in 'a'..'z' })
            assertFalse(message.contains(state.value))
        }
    }

    @Test
    fun missingContentUsesTargetedRepeatPrompt() {
        assertEquals(
            "请重新说要告诉亲友的内容",
            TaskState.NEEDS_CONTENT_REPEAT.userMessage(),
        )
    }

    @Test
    fun genericRetryKeepsWholeTaskPrompt() {
        assertEquals(
            "请重新说完整任务",
            TaskState.NEEDS_RETRY.userMessage(),
        )
    }

    @Test
    fun confirmationRequiresCompletedRehearsalOfCurrentSummary() {
        val state = TaskUiState(
            stage = TaskStage.ACTIVE,
            session = confirmationSession(),
        )

        assertFalse(state.canStartConfirmation(ConfirmationAction.CONFIRM_SEND, null))
        assertFalse(state.canStartConfirmation(ConfirmationAction.CONFIRM_SEND, "old-hash"))
        assertTrue(state.canStartConfirmation(ConfirmationAction.CONFIRM_SEND, "current-hash"))
    }

    @Test
    fun rehearsalStageAndUnlistedActionRemainBlocked() {
        val session = confirmationSession()

        assertFalse(
            TaskUiState(TaskStage.REHEARSING, session)
                .canStartConfirmation(ConfirmationAction.CONFIRM_SEND, "current-hash"),
        )
        assertFalse(
            TaskUiState(TaskStage.ACTIVE, session)
                .canStartConfirmation(ConfirmationAction.CONFIRM_CALL, "current-hash"),
        )
    }

    @Test
    fun activeFeedbackDistinguishesSelectionConfirmationAndRepeat() {
        val selection = TaskUiState(
            TaskStage.ACTIVE,
            confirmationSession().copy(state = TaskState.AWAITING_SELECTION),
        ).feedbackPresentation()
        val confirmation = TaskUiState(
            TaskStage.ACTIVE,
            confirmationSession(),
        ).feedbackPresentation()
        val repeat = TaskUiState(
            TaskStage.ACTIVE,
            confirmationSession().copy(state = TaskState.NEEDS_CONTENT_REPEAT),
        ).feedbackPresentation()

        assertEquals("请选联系人", selection.label)
        assertEquals("请说安全指令", confirmation.label)
        assertEquals("请重说消息内容", repeat.label)
        assertEquals(HapticCue.ATTENTION, repeat.hapticCue)
    }

    @Test
    fun processingAndExecutingFeedbackNeverRequestAnotherChoice() {
        listOf(TaskState.CREATED, TaskState.PROCESSING).forEach { taskState ->
            val presentation = TaskUiState(
                TaskStage.ACTIVE,
                confirmationSession().copy(state = taskState),
            ).feedbackPresentation()

            assertEquals(FeedbackSymbol.PROCESSING, presentation.symbol)
            assertEquals("正在处理", presentation.label)
            assertFalse(presentation.accessibilityLabel.contains("选择"))
            assertEquals(HapticCue.NONE, presentation.hapticCue)
        }

        val executing = TaskUiState(
            TaskStage.ACTIVE,
            confirmationSession().copy(state = TaskState.EXECUTING),
        ).feedbackPresentation()

        assertEquals(FeedbackSymbol.PROCESSING, executing.symbol)
        assertEquals("正在执行", executing.label)
        assertFalse(executing.accessibilityLabel.contains("选择"))
        assertEquals(HapticCue.NONE, executing.hapticCue)
    }

    @Test
    fun simulatedTerminalFeedbackExplicitlySaysWechatWasNotCalled() {
        val presentation = TaskUiState(
            TaskStage.COMPLETED,
            confirmationSession().copy(state = TaskState.SIMULATED),
        ).feedbackPresentation()

        assertEquals(FeedbackSymbol.SUCCESS, presentation.symbol)
        assertEquals("体验已完成", presentation.label)
        assertTrue(presentation.accessibilityLabel.contains("没有调用微信"))
    }

    @Test
    fun terminalFailureNeverUsesSuccessFeedback() {
        val presentation = TaskUiState(
            TaskStage.COMPLETED,
            confirmationSession().copy(state = TaskState.FAILED),
        ).feedbackPresentation()

        assertEquals(FeedbackSymbol.ERROR, presentation.symbol)
        assertEquals(HapticCue.ERROR, presentation.hapticCue)
        assertEquals("没有完成", presentation.label)
    }

    @Test
    fun onlySimulatedTerminalCanRestartExperience() {
        val simulated = TaskUiState(
            TaskStage.COMPLETED,
            confirmationSession().copy(state = TaskState.SIMULATED),
        )

        assertTrue(simulated.canRestartSimulation())
        listOf(TaskState.COMPLETED, TaskState.PARTIAL, TaskState.FAILED).forEach { realTerminalState ->
            assertFalse(
                simulated.copy(session = simulated.session?.copy(state = realTerminalState))
                    .canRestartSimulation(),
            )
        }
        assertFalse(simulated.copy(stage = TaskStage.ACTIVE).canRestartSimulation())
    }

    @Test
    fun microphoneDenialKeepsCurrentSessionAndStage() {
        val original = TaskUiState(
            stage = TaskStage.ACTIVE,
            session = confirmationSession(),
            pendingConfirmationAction = ConfirmationAction.CONFIRM_SEND,
            continuation = MessageContinuationUi("亲友", 4),
        )

        val denied = original.withMicrophonePermissionDenied()

        assertEquals(original.stage, denied.stage)
        assertEquals(original.session, denied.session)
        assertEquals(original.pendingConfirmationAction, denied.pendingConfirmationAction)
        assertEquals(original.continuation, denied.continuation)
        assertTrue(denied.microphonePermissionRecoveryRequired)
        assertEquals("需要允许麦克风权限才能录音", denied.statusMessage)
        assertEquals("未允许麦克风权限；本次没有录音、上传或执行任何任务", denied.errorMessage)
    }

    @Test
    fun taskAudioConsentRequiresExactTypeDecisionAndCurrentPolicy() {
        val decidedAt = OffsetDateTime.parse("2026-09-01T12:00:00Z")
        fun consent(
            type: ConsentType = ConsentType.TASK_AUDIO,
            decision: ConsentDecision = ConsentDecision.GRANTED,
            policy: String = "task-audio-v1",
        ) = Consent(type, decision, policy, decidedAt)

        assertTrue(listOf(consent()).hasTaskAudioConsent("task-audio-v1"))
        assertFalse(
            listOf(consent(decision = ConsentDecision.REVOKED))
                .hasTaskAudioConsent("task-audio-v1"),
        )
        assertFalse(
            listOf(consent(type = ConsentType.VOICE_TEMPLATE))
                .hasTaskAudioConsent("task-audio-v1"),
        )
        assertFalse(
            listOf(consent(policy = "task-audio-old"))
                .hasTaskAudioConsent("task-audio-v1"),
        )
    }

    private fun confirmationSession() = TaskSession(
        sessionId = "ts_confirmation",
        sessionVersion = 1,
        state = TaskState.AWAITING_CONFIRMATION,
        candidates = emptyList(),
        allowedActions = setOf(AllowedAction.CONFIRM_SEND),
        expiresAt = OffsetDateTime.parse("2026-08-22T12:00:00Z"),
        summaryHash = "current-hash",
    )
}
