package com.aifriend.voicecollection.application;

import java.util.UUID;

/**
 * 训练数据集撤权清理端口。
 *
 * <p>任何采集删除或训练撤权都通过本端口立即清除受影响的数据集选择记录，
 * 不允许仅依赖后续读取失败。</p>
 *
 * @author codex
 * @since 1.0.0
 */
public interface VoiceTrainingDatasetCleanupPort {

    /**
     * 删除当前 owner 的全部训练数据集。
     *
     * @param ownerUserId 当前 owner UUID
     */
    void deleteAll(UUID ownerUserId);

    /**
     * 删除包含指定样本的全部训练数据集。
     *
     * @param ownerUserId 当前 owner UUID
     * @param sampleId 已删除或撤权的样本 UUID
     */
    void deleteContaining(UUID ownerUserId, UUID sampleId);
}
