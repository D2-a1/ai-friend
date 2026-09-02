package com.aifriend.voice.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.aifriend.voice.domain.AudioObject;

/**
 * 临时音频对象持久化端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface AudioObjectRepositoryPort {

    /**
     * 锁定 owner 账号行，串行化同一用户的凭证创建和幂等轮换。
     *
     * @param ownerUserId 当前用户 UUID
     */
    void lockOwner(UUID ownerUserId);

    /**
     * 按 owner 和幂等键摘要加锁查询音频对象。
     *
     * @param ownerUserId 当前用户 UUID
     * @param idempotencyKeyDigest 幂等键摘要
     * @return owner 范围内的原音频对象
     */
    Optional<AudioObject> findByIdempotencyKeyForUpdate(
            UUID ownerUserId,
            byte[] idempotencyKeyDigest);

    /**
     * 按音频对象 UUID 加锁查询，供上传秘密鉴权使用。
     *
     * @param audioObjectId 音频对象 UUID
     * @return 加锁后的音频对象
     */
    Optional<AudioObject> findByIdForUpdate(UUID audioObjectId);

    /**
     * 按音频对象和 owner 范围查询无锁快照。
     *
     * <p>只用于事务外读取和内容校验，不能直接推进业务状态。
     *
     * @param audioObjectId 音频对象 UUID
     * @param ownerUserId 当前用户 UUID
     * @return owner 范围内的音频对象快照
     */
    Optional<AudioObject> findByIdAndOwner(UUID audioObjectId, UUID ownerUserId);

    /**
     * 按音频对象和 owner 范围加写锁查询。
     *
     * @param audioObjectId 音频对象 UUID
     * @param ownerUserId 当前用户 UUID
     * @return owner 范围内加锁后的音频对象
     */
    Optional<AudioObject> findByIdAndOwnerForUpdate(
            UUID audioObjectId,
            UUID ownerUserId);

    /**
     * 查询一批已超过上传期限的未消费对象。
     *
     * @param now 当前 UTC 时间
     * @param batchSize 最大返回数量
     * @return 按过期时间升序的有界对象列表
     */
    List<AudioObject> findExpiredIssued(Instant now, int batchSize);

    /**
     * 保存音频对象元数据，不保存原始音频。
     *
     * @param audioObject 音频对象快照
     * @return 保存后的音频对象
     */
    AudioObject save(AudioObject audioObject);
}
