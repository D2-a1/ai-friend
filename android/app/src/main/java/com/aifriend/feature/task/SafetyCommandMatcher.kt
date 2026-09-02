package com.aifriend.feature.task

import com.aifriend.contract.model.ConfirmationAction
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
