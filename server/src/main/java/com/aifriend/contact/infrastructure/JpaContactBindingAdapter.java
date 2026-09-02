package com.aifriend.contact.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.aifriend.contact.application.ContactBindingPage;
import com.aifriend.contact.application.ContactBindingRepositoryPort;
import com.aifriend.contact.domain.ContactBinding;
import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.identity.infrastructure.AppUserJpaRepository;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * MySQL 联系人绑定 owner 范围查询适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JpaContactBindingAdapter implements ContactBindingRepositoryPort {

    private static final List<ContactStatus> OCCUPYING_STATUSES = List.of(
            ContactStatus.PENDING_CONSENT,
            ContactStatus.PENDING_LOCAL_VERIFY,
            ContactStatus.ACTIVE_NO_ALIAS,
            ContactStatus.ACTIVE,
            ContactStatus.REVERIFY_REQUIRED,
            ContactStatus.BLOCKED);

    private final ContactBindingJpaRepository repository;
    private final AppUserJpaRepository userRepository;

    /**
     * 创建联系人绑定查询适配器。
     *
     * @param repository Spring Data Repository
     * @param userRepository owner 账号锁 Repository
     */
    public JpaContactBindingAdapter(
            ContactBindingJpaRepository repository,
            AppUserJpaRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    /**
     * 锁定 owner 账号，串行化稳定定位唯一性校验与写入。
     *
     * @param ownerUserId owner UUID
     * @throws BusinessException 当当前已认证账号不存在时抛出
     */
    @Override
    public void lockOwner(UUID ownerUserId) {
        userRepository.findByIdForUpdate(ownerUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
    }

    /**
     * 统计 owner 的所有未解除绑定。
     *
     * @param ownerUserId owner UUID
     * @return 占用联系人上限的绑定数量
     */
    @Override
    public long countOccupying(UUID ownerUserId) {
        return repository.countByOwnerUserIdAndStatusIn(ownerUserId, OCCUPYING_STATUSES);
    }

    /**
     * 按 owner 与微信主体 HMAC 加锁查询全生命周期唯一绑定。
     *
     * @param ownerUserId owner UUID
     * @param contactSubjectHash 微信主体 HMAC
     * @return 已存在绑定
     */
    @Override
    public Optional<ContactBinding> findByOwnerAndSubjectForUpdate(
            UUID ownerUserId,
            byte[] contactSubjectHash) {
        return repository.findByOwnerAndSubjectForUpdate(ownerUserId, contactSubjectHash)
                .map(this::toDomain);
    }

    /**
     * 按 owner 与联系人 UUID 加锁查询。
     *
     * @param ownerUserId owner UUID
     * @param contactId 联系人绑定 UUID
     * @return owner 范围联系人
     */
    @Override
    public Optional<ContactBinding> findByOwnerAndIdForUpdate(
            UUID ownerUserId,
            UUID contactId) {
        return repository.findByOwnerAndIdForUpdate(ownerUserId, contactId)
                .map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ContactBinding> findByOwnerAndId(UUID ownerUserId, UUID contactId) {
        return repository.findByOwnerUserIdAndId(ownerUserId, contactId).map(this::toDomain);
    }

    /**
     * 判断同一 owner 的其他联系人是否已占用稳定定位 HMAC。
     *
     * @param ownerUserId owner UUID
     * @param contactId 当前联系人绑定 UUID
     * @param locatorHash 稳定定位 HMAC
     * @return 存在冲突绑定时返回 true
     */
    @Override
    public boolean existsOtherByOwnerAndLocatorHash(
            UUID ownerUserId,
            UUID contactId,
            byte[] locatorHash) {
        return repository.existsByOwnerUserIdAndIdNotAndWechatLocatorHash(
                ownerUserId, contactId, locatorHash);
    }

    /**
     * 保存联系人绑定及亲友同意证据。
     *
     * @param binding 绑定快照
     * @return 保存后的绑定
     */
    @Override
    public ContactBinding save(ContactBinding binding) {
        ContactBindingEntity saved = repository.save(new ContactBindingEntity(
                binding.id(), binding.ownerUserId(), binding.contactSubjectHash(),
                binding.contactSubjectCipher(), binding.wechatLocatorCipher(),
                binding.wechatLocatorHash(), binding.remarkCipher(),
                binding.wechatVersion(), binding.localVerificationVersion(),
                binding.verificationIdempotencyKeyHash(), binding.verificationRequestHash(),
                binding.unbindIdempotencyKeyHash(), binding.unbindRequestHash(),
                binding.verifiedAt(),
                binding.relationship(), binding.consentPolicyVersion(), binding.consentedAt(),
                binding.status(), binding.createdBy(), binding.version(), binding.createdAt(),
                binding.updatedAt(), binding.revokedAt()));
        return toDomain(saved);
    }

    /**
     * 按 owner 与可选状态分页查询，按更新时间和 UUID 倒序稳定排序。
     *
     * @param ownerUserId owner UUID
     * @param status 可选状态
     * @param page 页码
     * @param size 每页数量
     * @return owner 范围分页结果
     */
    @Override
    public ContactBindingPage findByOwner(
            UUID ownerUserId,
            ContactStatus status,
            int page,
            int size) {
        PageRequest pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
        Page<ContactBindingEntity> entities = status == null
                ? repository.findByOwnerUserId(ownerUserId, pageable)
                : repository.findByOwnerUserIdAndStatus(ownerUserId, status, pageable);
        return new ContactBindingPage(
                entities.getContent().stream().map(this::toDomain).toList(),
                entities.getNumber(),
                entities.getSize(),
                entities.getTotalElements(),
                entities.getTotalPages());
    }

    private ContactBinding toDomain(ContactBindingEntity entity) {
        return new ContactBinding(
                entity.getId(),
                entity.getOwnerUserId(),
                entity.getContactSubjectHash(),
                entity.getContactSubjectCipher(),
                entity.getWechatLocatorCipher(),
                entity.getWechatLocatorHash(),
                entity.getRemarkCipher(),
                entity.getWechatVersion(),
                entity.getLocalVerificationVersion(),
                entity.getVerificationIdempotencyKeyHash(),
                entity.getVerificationRequestHash(),
                entity.getUnbindIdempotencyKeyHash(),
                entity.getUnbindRequestHash(),
                entity.getVerifiedAt(),
                entity.getRelationship(),
                entity.getConsentPolicyVersion(),
                entity.getConsentedAt(),
                entity.getStatus(),
                entity.getCreatedBy(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getRevokedAt());
    }
}
