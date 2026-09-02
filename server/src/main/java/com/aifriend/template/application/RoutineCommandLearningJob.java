package com.aifriend.template.application;

import java.time.Instant;
import java.util.UUID;

import com.aifriend.task.domain.TaskIntent;

/**
 * 已取得短时租约的日常指令学习任务。
 *
 * @param id Outbox UUID
 * @param taskSessionId 来源任务会话 UUID
 * @param ownerUserId owner UUID
 * @param audioObjectId 已消费 TASK 音频 UUID
 * @param intent 有限通信意图
 * @param actionStartMs 动作原声起点毫秒
 * @param actionEndMs 动作原声终点毫秒
 * @param dialectCode 方言代码
 * @param dialectPackageVersion 方言包版本
 * @param templateModelVersion 模板模型版本
 * @param thresholdVersion 阈值版本
 * @param attempts 已领取次数
 * @param leaseToken 当前工作器租约 UUID
 * @param sourceRetentionUntil 来源音频留存截止时间
 * @param createdAt 入队时间
 * @author Codex
 * @since 1.0.0
 */
public record RoutineCommandLearningJob(
        UUID id,
        UUID taskSessionId,
        UUID ownerUserId,
        UUID audioObjectId,
        TaskIntent intent,
        int actionStartMs,
        int actionEndMs,
        String dialectCode,
        String dialectPackageVersion,
        String templateModelVersion,
        String thresholdVersion,
        int attempts,
        UUID leaseToken,
        Instant sourceRetentionUntil,
        Instant createdAt) {
}
