package com.aifriend.voicecollection.application;

import java.util.UUID;

import com.aifriend.voicecollection.domain.VoiceCollectionCategory;
import com.aifriend.voicecollection.domain.VoiceCollectionEnvironment;

/**
 * 不可变训练数据集成员摘要。
 *
 * @param sampleId 样本 UUID
 * @param ordinal 数据集内固定顺序，从零开始
 * @param sampleVersion 冻结时样本版本
 * @param audioSha256 音频内容 SHA-256
 * @param reviewedTranscriptSha256 人工复核密文 SHA-256
 * @param category 采集类别
 * @param promptCode 固定提示编码
 * @param environment 录音环境
 * @param dialectCode 方言代码
 * @author codex
 * @since 1.0.0
 */
public record VoiceTrainingDatasetMember(
        UUID sampleId,
        int ordinal,
        long sampleVersion,
        byte[] audioSha256,
        byte[] reviewedTranscriptSha256,
        VoiceCollectionCategory category,
        String promptCode,
        VoiceCollectionEnvironment environment,
        String dialectCode) {
}
