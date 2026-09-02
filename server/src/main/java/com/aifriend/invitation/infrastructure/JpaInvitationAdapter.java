package com.aifriend.invitation.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.aifriend.identity.infrastructure.AppUserJpaRepository;
import com.aifriend.invitation.application.InvitationRepositoryPort;
import com.aifriend.invitation.domain.ContactInvitation;
import com.aifriend.invitation.domain.InvitationStatus;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * MySQL 亲友邀请持久化适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JpaInvitationAdapter implements InvitationRepositoryPort {

    private static final List<InvitationStatus> PENDING_STATUSES = List.of(
            InvitationStatus.PENDING,
            InvitationStatus.PROOF_REDEEMED,
            InvitationStatus.WECHAT_VERIFIED);

    private final ContactInvitationJpaRepository invitationRepository;
    private final AppUserJpaRepository userRepository;

    /**
     * 创建邀请持久化适配器。
     *
     * @param invitationRepository 邀请 Repository
     * @param userRepository 用户 Repository
     */
    public JpaInvitationAdapter(
            ContactInvitationJpaRepository invitationRepository,
            AppUserJpaRepository userRepository) {
        this.invitationRepository = invitationRepository;
        this.userRepository = userRepository;
    }

    /**
     * 锁定邀请人账号行，串行化同一账号的邀请写操作。
     *
     * @param ownerUserId 邀请人 UUID
     * @throws BusinessException 当邀请人不存在时抛出
     */
    @Override
    public void lockOwner(UUID ownerUserId) {
        userRepository.findByIdForUpdate(ownerUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
    }

    /**
     * 将已到期的未完成邀请批量推进到 EXPIRED 终态。
     *
     * @param ownerUserId 邀请人 UUID
     * @param now 当前 UTC 时间
     */
    @Override
    public void expirePending(UUID ownerUserId, Instant now) {
        invitationRepository.expirePending(
                ownerUserId, PENDING_STATUSES, InvitationStatus.EXPIRED, now);
    }

    /**
     * 按邀请人与创建幂等键摘要查询原邀请。
     *
     * @param ownerUserId 邀请人 UUID
     * @param idempotencyKeyHash 创建幂等键摘要
     * @return 原邀请，不存在时为空
     */
    @Override
    public Optional<ContactInvitation> findByCreateIdempotencyKey(
            UUID ownerUserId, byte[] idempotencyKeyHash) {
        return invitationRepository
                .findByOwnerUserIdAndCreateIdempotencyKeyHash(ownerUserId, idempotencyKeyHash)
                .map(this::toDomain);
    }

    /**
     * 在邀请人范围内加锁查询邀请，避免泄露其他用户的资源是否存在。
     *
     * @param invitationId 邀请 UUID
     * @param ownerUserId 邀请人 UUID
     * @return 匹配的邀请，不存在或不属于邀请人时为空
     */
    @Override
    public Optional<ContactInvitation> findByIdForUpdate(UUID invitationId, UUID ownerUserId) {
        return invitationRepository.findByIdAndOwnerUserId(invitationId, ownerUserId).map(this::toDomain);
    }

    /**
     * 按 UUID 加锁查询公开邀请兑换目标。
     *
     * @param invitationId 邀请 UUID
     * @return 邀请，不存在时为空
     */
    @Override
    public Optional<ContactInvitation> findByIdForUpdate(UUID invitationId) {
        return invitationRepository.findByIdForUpdate(invitationId).map(this::toDomain);
    }

    /**
     * 非锁定读取邀请，只用于先确定 owner 并建立统一锁顺序。
     *
     * @param invitationId 邀请 UUID
     * @return 邀请，不存在时为空
     */
    @Override
    public Optional<ContactInvitation> findById(UUID invitationId) {
        return invitationRepository.findById(invitationId).map(this::toDomain);
    }

    /**
     * 统计占用配额的未完成邀请。
     *
     * @param ownerUserId 邀请人 UUID
     * @return 未完成邀请数
     */
    @Override
    public long countPending(UUID ownerUserId) {
        return invitationRepository.countByOwnerUserIdAndStatusIn(ownerUserId, PENDING_STATUSES);
    }

    /**
     * 查询当前用户仍未过期的未完成邀请。
     *
     * @param ownerUserId 邀请人 UUID
     * @param now 当前 UTC 时间
     * @param limit 最大返回数量
     * @return 按创建时间倒序排列的邀请快照
     */
    @Override
    public List<ContactInvitation> findPending(UUID ownerUserId, Instant now, int limit) {
        return invitationRepository
                .findByOwnerUserIdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                        ownerUserId, PENDING_STATUSES, now, PageRequest.of(0, limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    /**
     * 统计 UTC 当日起点之后创建的全部邀请。
     *
     * @param ownerUserId 邀请人 UUID
     * @param dayStart UTC 当日起点
     * @return 当日创建数
     */
    @Override
    public long countCreatedSince(UUID ownerUserId, Instant dayStart) {
        return invitationRepository.countByOwnerUserIdAndCreatedAtGreaterThanEqual(ownerUserId, dayStart);
    }

    /**
     * 保存不含 proof 明文的邀请快照。
     *
     * @param invitation 邀请快照
     * @return 保存后的邀请
     */
    @Override
    public ContactInvitation save(ContactInvitation invitation) {
        ContactInvitationEntity saved = invitationRepository.save(new ContactInvitationEntity(
                invitation.id(), invitation.ownerUserId(), invitation.proofDigest(), invitation.status(),
                invitation.expiresAt(), invitation.createdAt(), invitation.createIdempotencyKeyHash(),
                invitation.revokeIdempotencyKeyHash(), invitation.version()));
        return toDomain(saved);
    }

    private ContactInvitation toDomain(ContactInvitationEntity entity) {
        return new ContactInvitation(
                entity.getId(), entity.getOwnerUserId(), entity.getProofDigest(), entity.getStatus(),
                entity.getExpiresAt(), entity.getCreatedAt(), entity.getCreateIdempotencyKeyHash(),
                entity.getRevokeIdempotencyKeyHash(), entity.getVersion());
    }
}
