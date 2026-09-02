package com.aifriend.invitation.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.aifriend.invitation.domain.ContactInvitation;

/**
 * 亲友邀请持久化端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface InvitationRepositoryPort {

    /**
     * 锁定邀请人账号，串行化同一邀请人的数量复验。
     *
     * @param ownerUserId 邀请人 UUID
     */
    void lockOwner(UUID ownerUserId);

    /**
     * 将邀请人的过期待处理邀请更新为过期终态。
     *
     * @param ownerUserId 邀请人 UUID
     * @param now 当前 UTC 时间
     */
    void expirePending(UUID ownerUserId, Instant now);

    /**
     * 按创建幂等键摘要查询邀请。
     *
     * @param ownerUserId 邀请人 UUID
     * @param idempotencyKeyHash 幂等键摘要
     * @return 原邀请，不存在时为空
     */
    Optional<ContactInvitation> findByCreateIdempotencyKey(UUID ownerUserId, byte[] idempotencyKeyHash);

    /**
     * 按 owner 范围加锁查询邀请。
     *
     * @param invitationId 邀请 UUID
     * @param ownerUserId 邀请人 UUID
     * @return 邀请，不存在或不属于 owner 时为空
     */
    Optional<ContactInvitation> findByIdForUpdate(UUID invitationId, UUID ownerUserId);

    /**
     * 按公开邀请编号对应的 UUID 加锁查询，不返回其他邀请信息。
     *
     * @param invitationId 邀请 UUID
     * @return 邀请，不存在时为空
     */
    Optional<ContactInvitation> findByIdForUpdate(UUID invitationId);

    /**
     * 按 UUID 非锁定读取邀请，用于确定 owner 后遵循 owner→邀请→会话锁顺序。
     *
     * @param invitationId 邀请 UUID
     * @return 邀请，不存在时为空
     */
    default Optional<ContactInvitation> findById(UUID invitationId) {
        return findByIdForUpdate(invitationId);
    }

    /**
     * 统计当前未完成邀请。
     *
     * @param ownerUserId 邀请人 UUID
     * @return 未完成邀请数
     */
    long countPending(UUID ownerUserId);

    /**
     * 查询当前用户仍未过期的未完成邀请，按创建时间倒序返回。
     *
     * @param ownerUserId 邀请人 UUID
     * @param now 当前 UTC 时间
     * @param limit 最大返回数量
     * @return 不含 proof 明文的邀请快照
     */
    List<ContactInvitation> findPending(UUID ownerUserId, Instant now, int limit);

    /**
     * 统计 UTC 当日已创建邀请。
     *
     * @param ownerUserId 邀请人 UUID
     * @param dayStart UTC 当日起点
     * @return 当日创建数
     */
    long countCreatedSince(UUID ownerUserId, Instant dayStart);

    /**
     * 保存邀请快照。
     *
     * @param invitation 邀请快照
     * @return 保存后的邀请
     */
    ContactInvitation save(ContactInvitation invitation);
}
