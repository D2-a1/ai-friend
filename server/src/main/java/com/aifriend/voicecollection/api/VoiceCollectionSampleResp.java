package com.aifriend.voicecollection.api;

import java.time.Instant;

import com.aifriend.voicecollection.domain.VoiceCollectionCategory;
import com.aifriend.voicecollection.domain.VoiceCollectionEnvironment;
import com.aifriend.voicecollection.domain.VoiceCollectionReviewStatus;
import com.aifriend.voicecollection.domain.VoiceCollectionStatus;

/**
 * 采集样本最小响应。
 *
 * @param sampleId vs_ 前缀样本编号
 * @param category 样本类别
 * @param promptCode 固定提示编码
 * @param environment 环境分类
 * @param dialectCode 方言代码
 * @param status 当前状态
 * @param reviewStatus 人工复核状态
 * @param trainingEligible 是否已经同时取得全局训练同意和样本级明确授权
 * @param reviewedAt 人工复核完成时间；不返回复核文字
 * @param retentionUntil 最迟物理删除时间
 * @param version 乐观锁版本
 * @param createdAt 创建时间
 * @author Codex
 * @since 1.0.0
 */
public record VoiceCollectionSampleResp(
        String sampleId,
        VoiceCollectionCategory category,
        String promptCode,
        VoiceCollectionEnvironment environment,
        String dialectCode,
        VoiceCollectionStatus status,
        VoiceCollectionReviewStatus reviewStatus,
        boolean trainingEligible,
        Instant reviewedAt,
        Instant retentionUntil,
        long version,
        Instant createdAt) {
}
