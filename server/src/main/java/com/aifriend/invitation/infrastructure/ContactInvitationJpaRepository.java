package com.aifriend.invitation.infrastructure;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.aifriend.invitation.domain.InvitationStatus;

/**
 * 亲友邀请 Spring Data Repository。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface ContactInvitationJpaRepository extends JpaRepository<ContactInvitationEntity, UUID> {

    /**
     * 按 owner 和创建幂等键摘要查询邀请。
     *
     * @param ownerUserId 邀请人 UUID
     * @param createIdempotencyKeyHash 幂等键摘要
     * @return 原邀请
     */
    Optional<ContactInvitationEntity> findByOwnerUserIdAndCreateIdempotencyKeyHash(
            UUID ownerUserId, byte[] createIdempotencyKeyHash);

    /**
     * 按 owner 范围加锁查询邀请。
     *
     * @param id 邀请 UUID
     * @param ownerUserId 邀请人 UUID
     * @return 邀请实体
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ContactInvitationEntity> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);

    /**
     * 按 UUID 加锁查询公开邀请兑换目标。
     *
     * @param id 邀请 UUID
     * @return 邀请实体
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select invitation from ContactInvitationEntity invitation where invitation.id = :id")
    Optional<ContactInvitationEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * 统计指定状态的邀请。
     *
     * @param ownerUserId 邀请人 UUID
     * @param statuses 状态集合
     * @return 邀请数
     */
    long countByOwnerUserIdAndStatusIn(UUID ownerUserId, Collection<InvitationStatus> statuses);

    /**
     * 查询指定 owner 仍未过期的未完成邀请。
     *
     * @param ownerUserId 邀请人 UUID
     * @param statuses 未完成状态集合
     * @param now 当前 UTC 时间
     * @param pageable 返回数量限制
     * @return 按创建时间倒序排列的邀请
     */
    List<ContactInvitationEntity> findByOwnerUserIdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
            UUID ownerUserId,
            Collection<InvitationStatus> statuses,
            Instant now,
            Pageable pageable);

    /**
     * 统计指定时间之后创建的邀请。
     *
     * @param ownerUserId 邀请人 UUID
     * @param dayStart UTC 当日起点
     * @return 邀请数
     */
    long countByOwnerUserIdAndCreatedAtGreaterThanEqual(UUID ownerUserId, Instant dayStart);

    /**
     * 将已过期的等待邀请推进到 EXPIRED 终态。
     *
     * @param ownerUserId 邀请人 UUID
     * @param pendingStatuses 占用配额的未完成状态集合
     * @param expired 过期状态
     * @param now 当前时间
     * @return 更新行数
     */
    @Modifying
    @Query("update ContactInvitationEntity invitation set invitation.status = :expired, "
            + "invitation.version = invitation.version + 1 "
            + "where invitation.ownerUserId = :ownerUserId and invitation.status in :pendingStatuses "
            + "and invitation.expiresAt <= :now")
    int expirePending(@Param("ownerUserId") UUID ownerUserId,
            @Param("pendingStatuses") Collection<InvitationStatus> pendingStatuses,
            @Param("expired") InvitationStatus expired,
            @Param("now") Instant now);
}
