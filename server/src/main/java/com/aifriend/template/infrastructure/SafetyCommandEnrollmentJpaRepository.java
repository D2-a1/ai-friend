package com.aifriend.template.infrastructure;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 安全指令整批注册 Spring Data Repository。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface SafetyCommandEnrollmentJpaRepository
        extends JpaRepository<SafetyCommandEnrollmentEntity, UUID> {

    /**
     * 按 owner 与幂等键摘要查询注册批次。
     *
     * @param ownerUserId owner UUID
     * @param idempotencyKeyHash 幂等键摘要
     * @return 已完成批次
     */
    Optional<SafetyCommandEnrollmentEntity> findByOwnerUserIdAndIdempotencyKeyHash(
            UUID ownerUserId,
            byte[] idempotencyKeyHash);

    /**
     * 按 owner 与幂等键摘要加写锁查询注册批次。
     *
     * @param ownerUserId owner UUID
     * @param idempotencyKeyHash 幂等键摘要
     * @return 锁定的已完成批次
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select enrollment from SafetyCommandEnrollmentEntity enrollment "
            + "where enrollment.ownerUserId = :ownerUserId "
            + "and enrollment.idempotencyKeyHash = :idempotencyKeyHash")
    Optional<SafetyCommandEnrollmentEntity> findByOwnerAndKeyForUpdate(
            @Param("ownerUserId") UUID ownerUserId,
            @Param("idempotencyKeyHash") byte[] idempotencyKeyHash);
}
