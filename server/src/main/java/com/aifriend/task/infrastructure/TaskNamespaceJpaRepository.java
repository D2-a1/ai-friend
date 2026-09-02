package com.aifriend.task.infrastructure;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * owner 任务命名空间 Spring Data Repository。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface TaskNamespaceJpaRepository
        extends JpaRepository<TaskNamespaceEntity, UUID> {

    /**
     * 加写锁读取 owner 命名空间。
     *
     * @param ownerUserId owner UUID
     * @return 加锁实体
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select namespace from TaskNamespaceEntity namespace "
            + "where namespace.ownerUserId = :ownerUserId")
    Optional<TaskNamespaceEntity> findByOwnerForUpdate(
            @Param("ownerUserId") UUID ownerUserId);
}
