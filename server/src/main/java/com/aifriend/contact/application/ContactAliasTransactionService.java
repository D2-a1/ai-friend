package com.aifriend.contact.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.contact.domain.ContactAlias;
import com.aifriend.contact.domain.ContactAliasStatus;
import com.aifriend.contact.domain.ContactBinding;
import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.voice.application.AudioObjectConsumptionTransactionService;
import com.aifriend.voice.application.ValidatedAudioObject;

/**
 * 联系人称呼、双音频状态、联系人状态和命名空间的原子提交服务。
 *
 * <p>固定锁顺序为 owner 命名空间、联系人、音频 UUID 排序、owner 有效称呼集合。
 * 声学端口在本事务中只允许执行最多 100 个模板的本地有界比较，禁止网络调用。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class ContactAliasTransactionService {

    private static final long MAX_ALIASES_PER_CONTACT = 5;
    private static final long MAX_ALIASES_PER_OWNER = 100;

    private final AliasNamespaceRepositoryPort namespaceRepositoryPort;
    private final ContactBindingRepositoryPort bindingRepositoryPort;
    private final ContactAliasRepositoryPort aliasRepositoryPort;
    private final ConsentGrantQueryPort consentGrantQueryPort;
    private final AudioObjectConsumptionTransactionService audioTransactionService;
    private final AcousticTemplatePort acousticTemplatePort;
    private final SensitiveDataProtector sensitiveDataProtector;
    private final DigestService digestService;
    private final ContactAliasMapper aliasMapper;
    private final ContactAliasOutboxPort outboxPort;
    private final AuditEventPort auditEventPort;
    private final Clock clock;

    /**
     * 创建联系人称呼原子提交服务。
     *
     * @param namespaceRepositoryPort owner 称呼命名空间锁端口
     * @param bindingRepositoryPort 联系人绑定端口
     * @param aliasRepositoryPort 联系人称呼端口
     * @param consentGrantQueryPort 当前语音模板授权端口
     * @param audioTransactionService 音频批量消费事务服务
     * @param acousticTemplatePort 本地声学唯一性端口
     * @param sensitiveDataProtector 展示文字、提示和模板加密器
     * @param digestService 摘要与常量时间比较服务
     * @param aliasMapper 称呼展示映射器
     * @param outboxPort 称呼投影与缓存事件端口
     * @param auditEventPort 去标识化审计端口
     * @param clock UTC 时钟
     */
    public ContactAliasTransactionService(
            AliasNamespaceRepositoryPort namespaceRepositoryPort,
            ContactBindingRepositoryPort bindingRepositoryPort,
            ContactAliasRepositoryPort aliasRepositoryPort,
            ConsentGrantQueryPort consentGrantQueryPort,
            AudioObjectConsumptionTransactionService audioTransactionService,
            AcousticTemplatePort acousticTemplatePort,
            SensitiveDataProtector sensitiveDataProtector,
            DigestService digestService,
            ContactAliasMapper aliasMapper,
            ContactAliasOutboxPort outboxPort,
            AuditEventPort auditEventPort,
            Clock clock) {
        this.namespaceRepositoryPort = namespaceRepositoryPort;
        this.bindingRepositoryPort = bindingRepositoryPort;
        this.aliasRepositoryPort = aliasRepositoryPort;
        this.consentGrantQueryPort = consentGrantQueryPort;
        this.audioTransactionService = audioTransactionService;
        this.acousticTemplatePort = acousticTemplatePort;
        this.sensitiveDataProtector = sensitiveDataProtector;
        this.digestService = digestService;
        this.aliasMapper = aliasMapper;
        this.outboxPort = outboxPort;
        this.auditEventPort = auditEventPort;
        this.clock = clock;
    }

    /**
     * 在短事务内创建称呼并同时消费全部注册音频。
     *
     * @param ownerUserId 已认证 owner UUID
     * @param contactId 联系人绑定 UUID
     * @param idempotencyHash 创建幂等键摘要
     * @param requestHash 创建请求摘要
     * @param command 已规范化命令
     * @param validatedAudioObjects 事务外完成完整校验的两段音频
     * @param candidate 两遍一致后生成的模板候选
     * @return 创建成功或锁内安全重放的称呼
     * @throws BusinessException 状态、版本、上限、声学分类或音频快照变化时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public ContactAliasSummary create(
            UUID ownerUserId,
            UUID contactId,
            byte[] idempotencyHash,
            byte[] requestHash,
            CreateContactAliasCommand command,
            List<ValidatedAudioObject> validatedAudioObjects,
            AcousticEnrollmentCandidate candidate) {
        long namespaceVersion = namespaceRepositoryPort.lock(ownerUserId);
        ContactAlias replay = aliasRepositoryPort.findByOwnerAndCreateKeyForUpdate(
                ownerUserId, idempotencyHash).orElse(null);
        if (replay != null) {
            return validateReplay(replay, requestHash);
        }

        ContactBinding binding = bindingRepositoryPort
                .findByOwnerAndIdForUpdate(ownerUserId, contactId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        validateEligibility(ownerUserId, binding, command.expectedContactVersion());
        validateLimits(ownerUserId, contactId);

        return audioTransactionService.consumeAll(validatedAudioObjects, ignored -> {
            List<ExistingAcousticTemplate> existingTemplates = aliasRepositoryPort
                    .findActiveByOwner(ownerUserId).stream()
                    .map(aliasMapper::toAcousticTemplate)
                    .toList();
            validateUniqueness(acousticTemplatePort.classify(candidate, existingTemplates));

            Instant now = Instant.now(clock);
            ContactAlias alias = new ContactAlias(
                    UUID.randomUUID(), ownerUserId, contactId,
                    sensitiveDataProtector.encrypt(command.displayText()),
                    command.phoneticHint() == null ? null
                            : sensitiveDataProtector.encrypt(command.phoneticHint()),
                    candidate.dialectCode(), candidate.dialectPackageVersion(),
                    candidate.modelVersion(), candidate.thresholdVersion(),
                    sensitiveDataProtector.encryptBytes(candidate.template()),
                    digestService.sha256(candidate.template()),
                    ContactAliasStatus.ACTIVE, idempotencyHash, requestHash,
                    null, null, 0, now, now, null);
            ContactAlias saved = aliasRepositoryPort.save(alias);
            bindingRepositoryPort.save(binding.withAliasPresence(true, now));
            namespaceRepositoryPort.increment(ownerUserId, namespaceVersion, now);
            outboxPort.appendCreated(saved.id(), contactId, ownerUserId, now);
            auditEventPort.append(ownerUserId, "CONTACT_ALIAS_CREATE", "SUCCESS", null, now);
            return aliasMapper.toSummary(saved);
        });
    }

    private ContactAliasSummary validateReplay(ContactAlias replay, byte[] requestHash) {
        if (!digestService.constantTimeEquals(replay.createRequestHash(), requestHash)
                || replay.status() != ContactAliasStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        return aliasMapper.toSummary(replay);
    }

    private void validateEligibility(
            UUID ownerUserId,
            ContactBinding binding,
            long expectedContactVersion) {
        if (binding.status() != ContactStatus.ACTIVE_NO_ALIAS
                && binding.status() != ContactStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.LOCAL_VERIFICATION_REQUIRED);
        }
        if (binding.version() + 1 != expectedContactVersion) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        if (binding.consentedAt() == null || binding.consentPolicyVersion() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (!consentGrantQueryPort.isGranted(ownerUserId, ConsentType.VOICE_TEMPLATE)) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }
    }

    private void validateLimits(UUID ownerUserId, UUID contactId) {
        if (aliasRepositoryPort.countActiveByBinding(ownerUserId, contactId)
                >= MAX_ALIASES_PER_CONTACT
                || aliasRepositoryPort.countActiveByOwner(ownerUserId)
                >= MAX_ALIASES_PER_OWNER) {
            throw new BusinessException(ErrorCode.ALIAS_LIMIT_REACHED);
        }
    }

    private void validateUniqueness(AcousticUniqueness uniqueness) {
        if (uniqueness == null) {
            throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
        }
        switch (uniqueness) {
            case DISTINCT -> { }
            case CONFLICT -> throw new BusinessException(ErrorCode.ALIAS_PHONETIC_CONFLICT);
            case BORDERLINE -> throw new BusinessException(ErrorCode.ALIAS_PHONETIC_BORDERLINE);
            default -> throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
        }
    }
}
