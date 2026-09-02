package com.aifriend.template.application;

import java.util.UUID;

import com.aifriend.task.domain.TaskIntent;

/**
 * 已确认任务写入日常指令学习 Outbox 的最小命令。
 *
 * @param taskSessionId 当前 owner 的任务会话 UUID
 * @param ownerUserId 当前已认证 owner UUID
 * @param audioObjectId 已被该任务消费的 TASK 音频 UUID
 * @param intent 有限通信意图
 * @param actionStartMs 明确动作原声起点毫秒
 * @param actionEndMs 明确动作原声终点毫秒
 * @param dialectCode 方言代码
 * @param dialectPackageVersion 已验签方言包版本
 * @param templateModelVersion 声学模板模型版本
 * @param thresholdVersion 阈值版本
 * @author Codex
 * @since 1.0.0
 */
public record RoutineCommandLearningRequest(
        UUID taskSessionId,
        UUID ownerUserId,
        UUID audioObjectId,
        TaskIntent intent,
        int actionStartMs,
        int actionEndMs,
        String dialectCode,
        String dialectPackageVersion,
        String templateModelVersion,
        String thresholdVersion) {
}
