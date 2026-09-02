package com.aifriend.template.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.template.application.SafetyCommandEnrollment;
import com.aifriend.template.application.SafetyCommandTemplateRepositoryPort;
import com.aifriend.template.domain.SafetyCommandTemplate;
import com.aifriend.template.domain.SafetyCommandTemplateStatus;

/**
 * MySQL 安全指令命名空间、注册批次与模板持久化适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JpaSafetyCommandTemplateAdapter
        implements SafetyCommandTemplateRepositoryPort {

    private final SafetyCommandNamespaceJpaRepository namespaceRepository;
    private final SafetyCommandEnrollmentJpaRepository enrollmentRepository;
    private final SafetyCommandTemplateJpaRepository templateRepository;

    /**
     * 创建 MySQL 安全指令持久化适配器。
     *
     * @param namespaceRepository owner 命名空间 Repository
     * @param enrollmentRepository 注册批次 Repository
     * @param templateRepository 声学模板 Repository
     */
    public JpaSafetyCommandTemplateAdapter(
            SafetyCommandNamespaceJpaRepository namespaceRepository,
            SafetyCommandEnrollmentJpaRepository enrollmentRepository,
            SafetyCommandTemplateJpaRepository templateRepository) {
        this.namespaceRepository = namespaceRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.templateRepository = templateRepository;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<SafetyCommandEnrollment> findEnrollment(
            UUID ownerUserId,
            byte[] idempotencyKeyHash) {
        return enrollmentRepository.findByOwnerUserIdAndIdempotencyKeyHash(
                ownerUserId, idempotencyKeyHash).map(this::toEnrollment);
    }

    /** {@inheritDoc} */
    @Override
    public long lockNamespace(UUID ownerUserId, Instant now) {
        SafetyCommandNamespaceEntity namespace = namespaceRepository
                .findByOwnerForUpdate(ownerUserId).orElse(null);
        if (namespace == null) {
            try {
                namespaceRepository.saveAndFlush(
                        new SafetyCommandNamespaceEntity(ownerUserId, 0, now));
            } catch (DataIntegrityViolationException exception) {
                throw new BusinessException(ErrorCode.SESSION_CONFLICT);
            }
            namespace = namespaceRepository.findByOwnerForUpdate(ownerUserId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_CONFLICT));
        }
        return namespace.getVersion();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<SafetyCommandEnrollment> findEnrollmentForUpdate(
            UUID ownerUserId,
            byte[] idempotencyKeyHash) {
        return enrollmentRepository.findByOwnerAndKeyForUpdate(
                ownerUserId, idempotencyKeyHash).map(this::toEnrollment);
    }

    /** {@inheritDoc} */
    @Override
    public List<SafetyCommandTemplate> findActiveByOwner(UUID ownerUserId) {
        return templateRepository.findByOwnerUserIdAndStatusOrderByCommandTypeAsc(
                ownerUserId, SafetyCommandTemplateStatus.ACTIVE).stream()
                .map(this::toDomain)
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<SafetyCommandTemplate> findActiveByOwnerAndId(
            UUID ownerUserId,
            UUID templateId) {
        return templateRepository.findByOwnerUserIdAndIdAndStatus(
                ownerUserId, templateId, SafetyCommandTemplateStatus.ACTIVE)
                .map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public int replaceActiveAndFlush(UUID ownerUserId, Instant now) {
        return templateRepository.replaceActive(ownerUserId, now);
    }

    /** {@inheritDoc} */
    @Override
    public SafetyCommandTemplate saveTemplate(SafetyCommandTemplate template) {
        return toDomain(templateRepository.save(toEntity(template)));
    }

    /** {@inheritDoc} */
    @Override
    public void saveEnrollment(SafetyCommandEnrollment enrollment) {
        enrollmentRepository.save(new SafetyCommandEnrollmentEntity(
                enrollment.id(), enrollment.ownerUserId(),
                enrollment.idempotencyKeyHash(), enrollment.requestHash(),
                enrollment.consentPolicyVersion(), enrollment.completedAt()));
    }

    /** {@inheritDoc} */
    @Override
    public void incrementNamespace(
            UUID ownerUserId,
            long expectedVersion,
            Instant now) {
        SafetyCommandNamespaceEntity namespace = namespaceRepository
                .findByOwnerForUpdate(ownerUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_CONFLICT));
        if (namespace.getVersion() != expectedVersion) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        namespace.increment(expectedVersion, now);
        namespaceRepository.save(namespace);
    }

    private SafetyCommandEnrollment toEnrollment(
            SafetyCommandEnrollmentEntity entity) {
        return new SafetyCommandEnrollment(
                entity.getId(), entity.getOwnerUserId(),
                entity.getIdempotencyKeyHash(), entity.getRequestHash(),
                entity.getConsentPolicyVersion(), entity.getCompletedAt());
    }

    private SafetyCommandTemplate toDomain(SafetyCommandTemplateEntity entity) {
        return new SafetyCommandTemplate(
                entity.getId(), entity.getEnrollmentId(), entity.getOwnerUserId(),
                entity.getCommandType(), entity.getDialectCode(),
                entity.getDialectPackageVersion(), entity.getTemplateModelVersion(),
                entity.getThresholdVersion(), entity.getTemplateCipher(),
                entity.getTemplateDigest(), entity.getStatus(), entity.getVersion(),
                entity.getCreatedAt(), entity.getUpdatedAt(), entity.getReplacedAt());
    }

    private SafetyCommandTemplateEntity toEntity(SafetyCommandTemplate template) {
        return new SafetyCommandTemplateEntity(
                template.id(), template.enrollmentId(), template.ownerUserId(),
                template.commandType(), template.dialectCode(),
                template.dialectPackageVersion(), template.modelVersion(),
                template.thresholdVersion(), template.templateCipher(),
                template.templateDigest(), template.status(), template.version(),
                template.createdAt(), template.updatedAt(), template.replacedAt());
    }
}
