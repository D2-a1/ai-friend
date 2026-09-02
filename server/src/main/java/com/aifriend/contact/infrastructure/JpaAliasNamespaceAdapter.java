package com.aifriend.contact.infrastructure;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.aifriend.contact.application.AliasNamespaceRepositoryPort;
import com.aifriend.identity.infrastructure.AppUserEntity;
import com.aifriend.identity.infrastructure.AppUserJpaRepository;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 基于用户账号行锁的称呼命名空间适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JpaAliasNamespaceAdapter implements AliasNamespaceRepositoryPort {

    private final AppUserJpaRepository repository;

    /**
     * 创建称呼命名空间适配器。
     *
     * @param repository 用户账号 Repository
     */
    public JpaAliasNamespaceAdapter(AppUserJpaRepository repository) {
        this.repository = repository;
    }

    /** {@inheritDoc} */
    @Override
    public long lock(UUID ownerUserId) {
        return findLocked(ownerUserId).getAliasNamespaceVersion();
    }

    /** {@inheritDoc} */
    @Override
    public void increment(UUID ownerUserId, long expectedVersion, Instant now) {
        AppUserEntity user = findLocked(ownerUserId);
        if (user.getAliasNamespaceVersion() != expectedVersion) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        user.incrementAliasNamespace(expectedVersion, now);
        repository.save(user);
    }

    private AppUserEntity findLocked(UUID ownerUserId) {
        return repository.findByIdForUpdate(ownerUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
    }
}
