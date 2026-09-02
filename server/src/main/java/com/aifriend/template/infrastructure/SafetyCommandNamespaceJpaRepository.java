package com.aifriend.template.infrastructure;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 安全指令 owner 命名空间 Spring Data Repository。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface SafetyCommandNamespaceJpaRepository
        extends JpaRepository<SafetyCommandNamespaceEntity, UUID> {

    /**
     * 按 owner 加写锁查询命名空间。
     *
     * @param ownerUserId owner UUID
     * @return 锁定的命名空间
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select namespace from SafetyCommandNamespaceEntity namespace "
            + "where namespace.ownerUserId = :ownerUserId")
    Optional<SafetyCommandNamespaceEntity> findByOwnerForUpdate(
            @Param("ownerUserId") UUID ownerUserId);
}
