package com.aifriend.task.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.aifriend.task.domain.TaskState;

/**
 * owner 范围任务会话 Spring Data Repository。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface TaskSessionJpaRepository extends JpaRepository<TaskSessionEntity, UUID> {

    /**
     * 按 owner 和创建幂等摘要查询会话。
     *
     * @param ownerUserId owner UUID
     * @param keyHash 创建幂等摘要
     * @return owner 范围会话
     */
    Optional<TaskSessionEntity> findByOwnerUserIdAndCreateIdempotencyKeyHash(
            UUID ownerUserId, byte[] keyHash);

    /**
     * 按 owner 和会话 UUID 查询会话。
     *
     * @param ownerUserId owner UUID
     * @param id 会话 UUID
     * @return owner 范围会话
     */
    Optional<TaskSessionEntity> findByOwnerUserIdAndId(UUID ownerUserId, UUID id);

    /**
     * 按 owner 查询最新创建的一条会话。
     *
     * @param ownerUserId owner UUID
     * @return owner 范围最新会话
     */
    Optional<TaskSessionEntity> findFirstByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId);

    /**
     * 按 owner 查询最近二十条指定状态会话。
     *
     * @param ownerUserId owner UUID
     * @param states 允许返回的终态集合
     * @return 按创建时间倒序排列的会话
     */
    List<TaskSessionEntity> findTop20ByOwnerUserIdAndStateInOrderByCreatedAtDesc(
            UUID ownerUserId, Collection<TaskState> states);

    /**
     * 按 owner 与会话 UUID 加写锁查询。
     *
     * @param ownerUserId owner UUID
     * @param id 会话 UUID
     * @return 加锁会话
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from TaskSessionEntity session "
            + "where session.ownerUserId = :ownerUserId and session.id = :id")
    Optional<TaskSessionEntity> findByOwnerAndIdForUpdate(
            @Param("ownerUserId") UUID ownerUserId,
            @Param("id") UUID id);
}
