package com.aifriend.contact.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.aifriend.contact.application.ContactAliasRepositoryPort;
import com.aifriend.contact.domain.ContactAlias;
import com.aifriend.contact.domain.ContactAliasStatus;

/**
 * MySQL 联系人称呼持久化适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JpaContactAliasAdapter implements ContactAliasRepositoryPort {

    private final ContactAliasJpaRepository repository;

    /**
     * 创建联系人称呼持久化适配器。
     *
     * @param repository Spring Data Repository
     */
    public JpaContactAliasAdapter(ContactAliasJpaRepository repository) {
        this.repository = repository;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ContactAlias> findByOwnerAndCreateKey(
            UUID ownerUserId,
            byte[] idempotencyKeyHash) {
        return repository.findByOwnerUserIdAndCreateIdempotencyKeyHash(
                ownerUserId, idempotencyKeyHash).map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ContactAlias> findByOwnerAndCreateKeyForUpdate(
            UUID ownerUserId,
            byte[] idempotencyKeyHash) {
        return repository.findByOwnerAndCreateKeyForUpdate(
                ownerUserId, idempotencyKeyHash).map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ContactAlias> findByOwnerAndBindingAndIdForUpdate(
            UUID ownerUserId,
            UUID bindingId,
            UUID aliasId) {
        return repository.findByOwnerAndBindingAndIdForUpdate(
                ownerUserId, bindingId, aliasId).map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public List<ContactAlias> findActiveByOwner(UUID ownerUserId) {
        return repository.findByOwnerUserIdAndStatusOrderByCreatedAtAscIdAsc(
                ownerUserId, ContactAliasStatus.ACTIVE).stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public long countActiveByOwner(UUID ownerUserId) {
        return repository.countByOwnerUserIdAndStatus(ownerUserId, ContactAliasStatus.ACTIVE);
    }

    /** {@inheritDoc} */
    @Override
    public long countActiveByBinding(UUID ownerUserId, UUID bindingId) {
        return repository.countByOwnerUserIdAndBindingIdAndStatus(
                ownerUserId, bindingId, ContactAliasStatus.ACTIVE);
    }

    /** {@inheritDoc} */
    @Override
    public ContactAlias save(ContactAlias alias) {
        return toDomain(repository.save(new ContactAliasEntity(
                alias.id(), alias.bindingId(), alias.ownerUserId(),
                alias.displayTextCipher(), alias.phoneticHintCipher(),
                alias.dialectCode(), alias.dialectPackageVersion(),
                alias.modelVersion(), alias.thresholdVersion(), alias.templateCipher(),
                alias.templateDigest(), alias.status(), alias.createIdempotencyKeyHash(),
                alias.createRequestHash(), alias.deleteIdempotencyKeyHash(),
                alias.deleteRequestHash(), alias.version(), alias.createdAt(),
                alias.updatedAt(), alias.deletedAt())));
    }

    private ContactAlias toDomain(ContactAliasEntity entity) {
        return new ContactAlias(
                entity.getId(), entity.getOwnerUserId(), entity.getBindingId(),
                entity.getDisplayTextCipher(), entity.getPhoneticHintCipher(),
                entity.getDialectCode(), entity.getDialectPackageVersion(),
                entity.getTemplateModelVersion(), entity.getThresholdVersion(),
                entity.getTemplateCipher(), entity.getTemplateDigest(), entity.getStatus(),
                entity.getCreateIdempotencyKeyHash(), entity.getCreateRequestHash(),
                entity.getDeleteIdempotencyKeyHash(), entity.getDeleteRequestHash(),
                entity.getVersion(), entity.getCreatedAt(), entity.getUpdatedAt(),
                entity.getDeletedAt());
    }
}
