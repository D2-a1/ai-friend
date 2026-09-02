package com.aifriend.template.application;

import java.time.Instant;

import com.aifriend.task.domain.TaskIntent;
import com.aifriend.template.domain.SafetyCommandType;

/**
 * 不含声学模板、音频或内部 UUID 的语音模板清单项。
 *
 * @param templateId 语音模板公开编号
 * @param category 模板分类
 * @param contactId 联系人公开编号，非称呼时为空
 * @param aliasId 称呼公开编号，非称呼时为空
 * @param safetyCommandType 安全指令类型，非安全指令时为空
 * @param routineCommandIntent 日常指令有限意图，非日常指令时为空
 * @param dialectCode 方言代码
 * @param dialectPackageVersion 方言包版本
 * @param modelVersion 声学模板模型版本
 * @param thresholdVersion 阈值版本
 * @param compatibility 当前已验证方言包兼容性
 * @param updatedAt 模板最后更新时间
 * @author Codex
 * @since 1.0.0
 */
public record VoiceTemplateSummary(
        String templateId,
        String category,
        String contactId,
        String aliasId,
        SafetyCommandType safetyCommandType,
        TaskIntent routineCommandIntent,
        String dialectCode,
        String dialectPackageVersion,
        String modelVersion,
        String thresholdVersion,
        String compatibility,
        Instant updatedAt) {
}
