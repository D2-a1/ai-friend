package com.aifriend.voicecollection.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 样本训练授权短事务存储端口。
 *
 * @author codex
 * @since 1.0.0
 */
public interface VoiceCollectionTrainingAuthorizationStorePort {

    /**
     * 锁定当前 owner，串行化样本授权与撤权。
     *
     * @param ownerUserId 当前 owner UUID
     */
    void lockOwner(UUID ownerUserId);

    /**
     * 查询同 owner、同幂等键的原授权事实。
     *
     * @param ownerUserId 当前 owner UUID
     * @param idempotencyKeyHash 幂等键 SHA-256
     * @return 原授权事实，不存在时为空
     */
    Optional<VoiceCollectionTrainingAuthorizationRecord> findByIdempotencyKey(
            UUID ownerUserId, byte[] idempotencyKeyHash);

    /**
     * 按 owner 锁定单条样本。
     *
     * @param ownerUserId 当前 owner UUID
     * @param sampleId 样本 UUID
     * @return 样本最小快照，不存在时为空
     */
    Optional<VoiceCollectionTrainingSample> findForUpdate(
            UUID ownerUserId, UUID sampleId);

    /**
     * 按状态和版本条件更新样本训练资格。
     *
     * @param ownerUserId 当前 owner UUID
     * @param sampleId 样本 UUID
     * @param expectedVersion 预期样本版本
     * @param trainingEligible 目标训练资格
     * @param updatedAt 更新时间
     * @return 是否精确更新一行
     */
    boolean updateEligibility(
            UUID ownerUserId,
            UUID sampleId,
            long expectedVersion,
            boolean trainingEligible,
            Instant updatedAt);

    /**
     * 追加一条不可覆盖的样本训练授权事实。
     *
     * @param ownerUserId 当前 owner UUID
     * @param record 授权事实
     */
    void append(UUID ownerUserId, VoiceCollectionTrainingAuthorizationRecord record);
}
