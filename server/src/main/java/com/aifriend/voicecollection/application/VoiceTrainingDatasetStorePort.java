package com.aifriend.voicecollection.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 个人训练数据集短事务存储端口。
 *
 * @author codex
 * @since 1.0.0
 */
public interface VoiceTrainingDatasetStorePort {

    /**
     * 锁定当前 owner，串行化数据集冻结与训练授权变化。
     *
     * @param ownerUserId 当前 owner UUID
     */
    void lockOwner(UUID ownerUserId);

    /**
     * 按 owner 和版本查询既有数据集。
     *
     * @param ownerUserId 当前 owner UUID
     * @param datasetVersion 数据集版本
     * @return 既有数据集，不存在时为空
     */
    Optional<VoiceTrainingDatasetRecord> findByVersion(
            UUID ownerUserId, String datasetVersion);

    /**
     * 锁定并读取当前可进入新数据集的样本。
     *
     * @param ownerUserId 当前 owner UUID
     * @param reviewPolicyVersion 当前人工复核政策版本
     * @param now 当前 UTC 时间
     * @param limit 最大返回数量
     * @return 当前合格样本
     */
    List<VoiceTrainingDatasetCandidate> findEligibleForUpdate(
            UUID ownerUserId,
            String reviewPolicyVersion,
            Instant now,
            int limit);

    /**
     * 锁定并读取既有数据集仍然有效的成员样本。
     *
     * @param ownerUserId 当前 owner UUID
     * @param datasetId 数据集 UUID
     * @param reviewPolicyVersion 当前人工复核政策版本
     * @param now 当前 UTC 时间
     * @param limit 最大返回数量
     * @return 仍满足全部实时门禁的成员样本
     */
    List<VoiceTrainingDatasetCandidate> findEligibleDatasetMembersForUpdate(
            UUID ownerUserId,
            UUID datasetId,
            String reviewPolicyVersion,
            Instant now,
            int limit);

    /**
     * 有界读取一个已冻结数据集的当前导出成员事实。
     *
     * <p>查询必须同时限定 owner 与数据集，并按冻结成员顺序返回；
     * 人工复核密文只供当前离线导出调用解密，调用结束必须清零。</p>
     *
     * @param ownerUserId 当前 owner UUID
     * @param datasetId 数据集 UUID
     * @param limit 最大返回数量
     * @return 按冻结顺序排列的导出成员
     */
    List<VoiceTrainingInputExportMember> findExportMembers(
            UUID ownerUserId,
            UUID datasetId,
            int limit);

    /**
     * 原子写入数据集头和全部成员摘要。
     *
     * @param dataset 数据集头
     * @param members 有序成员摘要
     */
    void insert(
            VoiceTrainingDatasetRecord dataset,
            List<VoiceTrainingDatasetMember> members);
}
