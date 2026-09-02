package com.aifriend.voice.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.aifriend.voice.domain.AudioObjectStatus;

/**
 * 临时音频对象 Spring Data Repository。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface AudioObjectJpaRepository extends JpaRepository<AudioObjectEntity, UUID> {

    /**
     * 按 owner 与幂等键摘要加写锁查询。
     *
     * @param ownerUserId owner UUID
     * @param idempotencyKeyHash 幂等键摘要
     * @return 加锁后的音频对象
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select audio from AudioObjectEntity audio "
            + "where audio.ownerUserId = :ownerUserId "
            + "and audio.idempotencyKeyHash = :idempotencyKeyHash")
    Optional<AudioObjectEntity> findByOwnerAndIdempotencyKeyForUpdate(
            @Param("ownerUserId") UUID ownerUserId,
            @Param("idempotencyKeyHash") byte[] idempotencyKeyHash);

    /**
     * 按音频对象 UUID 加写锁查询。
     *
     * @param id 音频对象 UUID
     * @return 加锁后的音频对象
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select audio from AudioObjectEntity audio where audio.id = :id")
    Optional<AudioObjectEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * 按对象与 owner 范围查询无锁快照。
     *
     * @param id 音频对象 UUID
     * @param ownerUserId owner UUID
     * @return owner 范围内的音频对象
     */
    Optional<AudioObjectEntity> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);

    /**
     * 按对象与 owner 范围加写锁查询。
     *
     * @param id 音频对象 UUID
     * @param ownerUserId owner UUID
     * @return owner 范围内加锁后的音频对象
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select audio from AudioObjectEntity audio "
            + "where audio.id = :id and audio.ownerUserId = :ownerUserId")
    Optional<AudioObjectEntity> findByIdAndOwnerForUpdate(
            @Param("id") UUID id,
            @Param("ownerUserId") UUID ownerUserId);

    /**
     * 分页查询上传期限已过且从未上传的对象。
     *
     * @param status 必须为 ISSUED
     * @param uploadExpiresAt 截止时间
     * @param pageable 有界分页
     * @return 按上传过期时间升序的对象页
     */
    Page<AudioObjectEntity>
            findByStatusAndUploadedAtIsNullAndUploadExpiresAtLessThanEqualOrderByUploadExpiresAtAsc(
                    AudioObjectStatus status,
                    Instant uploadExpiresAt,
                    Pageable pageable);
}
