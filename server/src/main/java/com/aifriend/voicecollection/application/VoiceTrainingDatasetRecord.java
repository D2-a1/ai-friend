package com.aifriend.voicecollection.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 已冻结训练数据集头记录。
 *
 * @param datasetId 数据集 UUID
 * @param ownerUserId 当前 owner UUID
 * @param datasetVersion 调用方指定的不可变版本
 * @param datasetPolicyVersion 数据集选择规则版本
 * @param trainingPolicyVersion 训练授权政策版本
 * @param reviewPolicyVersion 人工复核政策版本
 * @param sampleCount 样本数量
 * @param manifestSha256 规范化成员清单 SHA-256
 * @param createdAt 创建时间
 * @author codex
 * @since 1.0.0
 */
public record VoiceTrainingDatasetRecord(
        UUID datasetId,
        UUID ownerUserId,
        String datasetVersion,
        String datasetPolicyVersion,
        String trainingPolicyVersion,
        String reviewPolicyVersion,
        int sampleCount,
        byte[] manifestSha256,
        Instant createdAt) {
}
