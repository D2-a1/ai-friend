package com.aifriend.template.api;

import java.time.Instant;

import com.aifriend.task.domain.TaskIntent;
import com.aifriend.template.domain.SafetyCommandType;

/**
 * 语音模板最小元数据响应 DTO。
 *
 * @param templateId 模板公开编号
 * @param category 模板分类
 * @param contactId 联系人公开编号，可空
 * @param aliasId 称呼公开编号，可空
 * @param safetyCommandType 安全指令类型，可空
 * @param routineCommandIntent 日常指令有限意图，非日常指令时为空
 * @param dialectCode 方言代码
 * @param dialectPackageVersion 方言包版本
 * @param modelVersion 模板模型版本
 * @param thresholdVersion 阈值版本
 * @param compatibility 当前兼容性
 * @param updatedAt 最后更新时间
 * @author Codex
 * @since 1.0.0
 */
public record VoiceTemplateResp(
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
