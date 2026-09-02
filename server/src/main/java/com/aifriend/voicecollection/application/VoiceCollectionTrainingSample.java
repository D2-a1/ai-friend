package com.aifriend.voicecollection.application;

import java.time.Instant;
import java.util.UUID;

import com.aifriend.voicecollection.domain.VoiceCollectionReviewStatus;
import com.aifriend.voicecollection.domain.VoiceCollectionStatus;

/**
 * 训练授权事务内使用的样本最小快照。
 *
 * @param sampleId 样本 UUID
 * @param status 当前样本状态
 * @param trainingEligible 当前训练资格
 * @param reviewStatus 人工复核状态
 * @param retentionUntil 原始音频留存截止
 * @param version 乐观锁版本
 * @author codex
 * @since 1.0.0
 */
public record VoiceCollectionTrainingSample(
        UUID sampleId,
        VoiceCollectionStatus status,
        boolean trainingEligible,
        VoiceCollectionReviewStatus reviewStatus,
        Instant retentionUntil,
        long version) {
}
