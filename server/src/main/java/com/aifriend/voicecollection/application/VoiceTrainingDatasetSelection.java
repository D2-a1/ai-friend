package com.aifriend.voicecollection.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 已通过实时复验的训练数据集选择结果。
 *
 * <p>该结果只向后续内部训练导出用例提供样本 UUID，不包含音频对象键、
 * 人工复核文字或任何用户可见正文。</p>
 *
 * @param datasetId 数据集 UUID
 * @param datasetVersion 不可变数据集版本
 * @param sampleIds 按清单顺序排列的样本 UUID
 * @param manifestSha256 规范化清单 SHA-256
 * @param createdAt 数据集创建时间
 * @author codex
 * @since 1.0.0
 */
public record VoiceTrainingDatasetSelection(
        UUID datasetId,
        String datasetVersion,
        List<UUID> sampleIds,
        byte[] manifestSha256,
        Instant createdAt) {

    /**
     * 创建带不可变成员列表和摘要副本的选择结果。
     */
    public VoiceTrainingDatasetSelection {
        sampleIds = List.copyOf(sampleIds);
        manifestSha256 = manifestSha256.clone();
    }

    /**
     * 获取数据集清单摘要副本。
     *
     * @return 32 字节 SHA-256 副本
     */
    @Override
    public byte[] manifestSha256() {
        return manifestSha256.clone();
    }
}
