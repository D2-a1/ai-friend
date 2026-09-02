package com.aifriend.contact.infrastructure;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.aifriend.contact.domain.ContactStatus;

/**
 * 联系人绑定 Spring Data Repository。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface ContactBindingJpaRepository extends JpaRepository<ContactBindingEntity, UUID> {

    /**
     * 统计 owner 指定状态的绑定数量。
     *
     * @param ownerUserId owner UUID
     * @param statuses 占用上限的状态
     * @return 绑定数量
     */
    long countByOwnerUserIdAndStatusIn(
            UUID ownerUserId,
            Collection<ContactStatus> statuses);

    /**
     * 按 owner 与微信主体 HMAC 加写锁查询绑定。
     *
     * @param ownerUserId owner UUID
     * @param contactSubjectHash 微信主体 HMAC
     * @return 加锁后的绑定
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select binding from ContactBindingEntity binding "
            + "where binding.ownerUserId = :ownerUserId "
            + "and binding.contactSubjectHash = :contactSubjectHash")
    Optional<ContactBindingEntity> findByOwnerAndSubjectForUpdate(
            @Param("ownerUserId") UUID ownerUserId,
            @Param("contactSubjectHash") byte[] contactSubjectHash);

    /**
     * 按 owner 与绑定 UUID 加写锁查询联系人。
     *
     * @param ownerUserId owner UUID
     * @param contactId 联系人绑定 UUID
     * @return 加锁后的联系人实体
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select binding from ContactBindingEntity binding "
            + "where binding.ownerUserId = :ownerUserId and binding.id = :contactId")
    Optional<ContactBindingEntity> findByOwnerAndIdForUpdate(
            @Param("ownerUserId") UUID ownerUserId,
            @Param("contactId") UUID contactId);

    /**
     * 按 owner 与联系人 UUID 查询无锁快照。
     *
     * @param ownerUserId owner UUID
     * @param id 联系人绑定 UUID
     * @return owner 范围联系人实体
     */
    Optional<ContactBindingEntity> findByOwnerUserIdAndId(UUID ownerUserId, UUID id);

    /**
     * 判断同一 owner 的其他联系人是否已占用稳定定位。
     *
     * @param ownerUserId owner UUID
     * @param contactId 当前联系人绑定 UUID
     * @param locatorHash 稳定定位 HMAC
     * @return 存在冲突绑定时返回 true
     */
    boolean existsByOwnerUserIdAndIdNotAndWechatLocatorHash(
            UUID ownerUserId,
            UUID contactId,
            byte[] locatorHash);

    /**
     * 按 owner 分页查询绑定。
     *
     * @param ownerUserId owner UUID
     * @param pageable 分页与排序
     * @return owner 范围实体页
     */
    Page<ContactBindingEntity> findByOwnerUserId(UUID ownerUserId, Pageable pageable);

    /**
     * 按 owner 和状态分页查询绑定。
     *
     * @param ownerUserId owner UUID
     * @param status 状态过滤
     * @param pageable 分页与排序
     * @return owner 与状态范围实体页
     */
    Page<ContactBindingEntity> findByOwnerUserIdAndStatus(
            UUID ownerUserId,
            ContactStatus status,
            Pageable pageable);
}
