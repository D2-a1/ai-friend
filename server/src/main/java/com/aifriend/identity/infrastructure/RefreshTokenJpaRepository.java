package com.aifriend.identity.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/**
 * 刷新令牌 Spring Data Repository。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    /**
     * 按令牌摘要加悲观写锁查询。
     *
     * @param tokenHash 令牌摘要
     * @return 刷新令牌实体
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshTokenEntity> findByTokenHash(byte[] tokenHash);

    /**
     * 查询同一 family 全部令牌。
     *
     * @param familyId family UUID
     * @return 令牌实体列表
     */
    List<RefreshTokenEntity> findByFamilyId(UUID familyId);
}
