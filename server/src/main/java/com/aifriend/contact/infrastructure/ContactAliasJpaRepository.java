package com.aifriend.contact.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.aifriend.contact.domain.ContactAliasStatus;

/**
 * 联系人称呼 Spring Data Repository。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface ContactAliasJpaRepository extends JpaRepository<ContactAliasEntity, UUID> {

    /**
     * 按 owner 和创建幂等键查询。
     *
     * @param ownerUserId owner UUID
     * @param createIdempotencyKeyHash 创建幂等键摘要
     * @return 已存在称呼
     */
    Optional<ContactAliasEntity> findByOwnerUserIdAndCreateIdempotencyKeyHash(
            UUID ownerUserId,
            byte[] createIdempotencyKeyHash);

    /**
     * 按 owner 和创建幂等键加写锁查询。
     *
     * @param ownerUserId owner UUID
     * @param createIdempotencyKeyHash 创建幂等键摘要
     * @return 加锁后的称呼
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select alias from ContactAliasEntity alias "
            + "where alias.ownerUserId = :ownerUserId "
            + "and alias.createIdempotencyKeyHash = :createIdempotencyKeyHash")
    Optional<ContactAliasEntity> findByOwnerAndCreateKeyForUpdate(
            @Param("ownerUserId") UUID ownerUserId,
            @Param("createIdempotencyKeyHash") byte[] createIdempotencyKeyHash);

    /**
     * 按 owner、联系人和称呼编号加写锁查询。
     *
     * @param ownerUserId owner UUID
     * @param bindingId 联系人绑定 UUID
     * @param aliasId 称呼 UUID
     * @return 加锁后的称呼
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select alias from ContactAliasEntity alias "
            + "where alias.ownerUserId = :ownerUserId "
            + "and alias.bindingId = :bindingId and alias.id = :aliasId")
    Optional<ContactAliasEntity> findByOwnerAndBindingAndIdForUpdate(
            @Param("ownerUserId") UUID ownerUserId,
            @Param("bindingId") UUID bindingId,
            @Param("aliasId") UUID aliasId);

    /**
     * 查询 owner 指定状态的称呼。
     *
     * @param ownerUserId owner UUID
     * @param status 生命周期状态
     * @return 稳定排序的称呼列表
     */
    List<ContactAliasEntity> findByOwnerUserIdAndStatusOrderByCreatedAtAscIdAsc(
            UUID ownerUserId,
            ContactAliasStatus status);

    /**
     * 统计 owner 指定状态的称呼。
     *
     * @param ownerUserId owner UUID
     * @param status 生命周期状态
     * @return 称呼数量
     */
    long countByOwnerUserIdAndStatus(UUID ownerUserId, ContactAliasStatus status);

    /**
     * 统计联系人指定状态的称呼。
     *
     * @param ownerUserId owner UUID
     * @param bindingId 联系人绑定 UUID
     * @param status 生命周期状态
     * @return 称呼数量
     */
    long countByOwnerUserIdAndBindingIdAndStatus(
            UUID ownerUserId,
            UUID bindingId,
            ContactAliasStatus status);
}
