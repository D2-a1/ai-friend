package com.aifriend.feature.task

import com.aifriend.contract.model.ConfirmationAction
import com.aifriend.contract.model.SafetyCommandType
import com.aifriend.core.voice.LocalVoiceTemplateCoordinator
import com.aifriend.core.voice.LocalVoiceTemplateEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 使用本机加密的个人安全指令模板进行动作型确认匹配。
 *
 * 只返回模板编号，不判断说话人身份；无唯一可靠命中时返回空。
 *
 * @author codex
 * @since 2026-08-13
 */
@Singleton
class SafetyCommandMatcher @Inject constructor(
    private val coordinator: LocalVoiceTemplateCoordinator,
    private val engine: LocalVoiceTemplateEngine,
) {
    /**
     * 将既有四类个人方言安全模板恢复到任务确认链。
     *
     * 只有当前动作对应的确认模板才能确认；“取消这次”只能拒绝，
     * “重新说一遍”只能进入同一会话的完整重说。错类或非唯一匹配永不执行。
     */
    suspend fun decide(
        wavBytes: ByteArray,
        action: ConfirmationAction,
    ): VoiceConfirmationDecision {
        val templates = coordinator.loadAllSafetyCommands()
        return try {
            val requiredTypes = SafetyCommandType.entries.toSet()
            if (templates.size != requiredTypes.size ||
                templates.map { it.type }.toSet() != requiredTypes ||
                templates.map { it.templateId }.toSet().size != templates.size
            ) {
                return VoiceConfirmationDecision.UNAVAILABLE
            }
            val matchedId = engine.classify(
                wavBytes,
                templates.associate { it.templateId to it.candidate },
            ) ?: return VoiceConfirmationDecision.UNKNOWN
            when (templates.singleOrNull { it.templateId == matchedId }?.type) {
                action.requiredSafetyCommandType() -> VoiceConfirmationDecision.CONFIRM
                SafetyCommandType.CANCEL -> VoiceConfirmationDecision.REJECT
                SafetyCommandType.REJECT_RETRY -> VoiceConfirmationDecision.REPEAT
                SafetyCommandType.CONFIRM_SEND,
                SafetyCommandType.CONFIRM_CALL,
                null,
                -> VoiceConfirmationDecision.UNKNOWN
            }
        } finally {
            templates.forEach { it.clear() }
        }
    }

    suspend fun match(wavBytes: ByteArray, action: ConfirmationAction): String? {
        val required = action.requiredSafetyCommandType()
        val templates = coordinator.loadAllSafetyCommands()
        return try {
            val requiredTypes = com.aifriend.contract.model.SafetyCommandType.entries.toSet()
            if (templates.size != requiredTypes.size ||
                templates.map { template -> template.type }.toSet() != requiredTypes ||
                templates.map { template -> template.templateId }.toSet().size != templates.size
            ) {
                return null
            }
            val matched = engine.classify(
                wavBytes,
                templates.associate { template ->
                    template.templateId to template.candidate
                },
            )
            val requiredTemplateId = templates.singleOrNull { template ->
                template.type == required
            }?.templateId
            matched?.takeIf { it == requiredTemplateId }
        } finally {
            templates.forEach { template -> template.clear() }
        }
    }
}
private fun ConfirmationAction.requiredSafetyCommandType(): SafetyCommandType = when (this) {
    ConfirmationAction.CONFIRM_SEND -> SafetyCommandType.CONFIRM_SEND
    ConfirmationAction.CONFIRM_CALL -> SafetyCommandType.CONFIRM_CALL
    ConfirmationAction.CANCEL -> SafetyCommandType.CANCEL
    ConfirmationAction.REJECT -> SafetyCommandType.REJECT_RETRY
}
