package com.aifriend.feature.task

import com.aifriend.contract.model.ConfirmationAction
import com.aifriend.contract.model.ChannelPartResult
import com.aifriend.contract.model.ChannelResult
import com.aifriend.contract.model.RecentTaskResult
import com.aifriend.contract.model.SafetyCommandType
import com.aifriend.contract.model.TaskClientContext
import com.aifriend.contract.model.TaskSession
import com.aifriend.contract.model.WechatActionPlan
import com.aifriend.core.audio.CapturedAudio
import java.time.OffsetDateTime

/**
 * 当前任务会话网络仓库。
 *
 * @author codex
 * @since 2026-08-13
 */
interface TaskRepository {
    suspend fun listRecentResults(): List<RecentTaskResult>

    suspend fun create(
        audioObjectId: String,
        context: TaskClientContext,
        previousConfirmedContactId: String? = null,
        basicRecognitionAudio: CapturedAudio? = null,
    ): TaskSession

    suspend fun select(
        sessionId: String,
        candidateId: String,
        expectedVersion: Long,
    ): TaskSession

    suspend fun confirm(
        session: TaskSession,
        action: ConfirmationAction,
        templateId: String,
        recognizedAt: OffsetDateTime,
    ): TaskConfirmationOutcome

    suspend fun reportChannelResult(
        sessionId: String,
        plan: WechatActionPlan,
        result: ChannelResult,
        parts: List<ChannelPartResult>,
        occurredAt: OffsetDateTime,
    ): TaskSession
}

/** 确认接口返回的会话和只允许驻留当前任务内存的可空有限动作计划。 */
data class TaskConfirmationOutcome(
    val session: TaskSession,
    val actionPlan: WechatActionPlan?,
) {
    /** 避免任务转写、联系人、摘要和动作计划进入诊断文本。 */
    override fun toString(): String =
        "TaskConfirmationOutcome(sessionId=<redacted>, state=${session.state}, " +
            "actionPlanPresent=${actionPlan != null})"
}

/** 将会话允许动作映射为必须命中的个人安全指令类型。 */
fun ConfirmationAction.requiredSafetyCommandType(): SafetyCommandType = when (this) {
    ConfirmationAction.CONFIRM_SEND -> SafetyCommandType.CONFIRM_SEND
    ConfirmationAction.CONFIRM_CALL -> SafetyCommandType.CONFIRM_CALL
    ConfirmationAction.CANCEL -> SafetyCommandType.CANCEL
    ConfirmationAction.REJECT -> SafetyCommandType.REJECT_RETRY
}
