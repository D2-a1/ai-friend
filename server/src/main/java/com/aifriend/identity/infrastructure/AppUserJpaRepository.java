package com.aifriend.identity.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

/**
 * 用户账号 Spring Data Repository。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface AppUserJpaRepository extends JpaRepository<AppUserEntity, UUID> {

    /**
     * 按微信主体 HMAC 查询账号。
     *
     * @param wechatOpenIdHash 微信主体查询键
     * @return 用户实体
     */
    Optional<AppUserEntity> findByWechatOpenIdHash(byte[] wechatOpenIdHash);

    /**
     * 悲观锁定用户账号，串行化依赖用户级数量上限的写操作。
     *
     * @param id 用户 UUID
     * @return 用户实体
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from AppUserEntity user where user.id = :id")
    Optional<AppUserEntity> findByIdForUpdate(UUID id);
}
