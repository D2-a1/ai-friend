package com.aifriend.contact.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.aifriend.contact.domain.ContactBinding;
import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.contact.domain.WechatPageType;
import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.shared.security.SensitiveDataProtector;

/**
 * 本机微信联系人验证服务。
 *
 * <p>固定按 owner→联系人顺序加锁。客户端只提交 HTTPS 请求内的最小定位，服务端生成
 * AES-GCM 密文和域隔离 HMAC；页面证据、规则版本或唯一性不能确认时失败关闭。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class ContactVerificationService {

    private static final int REQUIRED_OBSERVATIONS = 1;

    private final ContactBindingRepositoryPort repositoryPort;
    private final SensitiveDataProtector sensitiveDataProtector;
    private final DigestService digestService;
    private final ContactVerificationProperties properties;
    private final ContactSummaryMapper summaryMapper;
    private final AuditEventPort auditEventPort;
    private final Clock clock;

    /**
     * 创建本机联系人验证服务。
     *
     * @param repositoryPort owner 范围联系人持久化端口
     * @param sensitiveDataProtector 定位和备注保护器
     * @param digestService 幂等键与请求指纹摘要服务
     * @param properties 失败关闭规则白名单与证据时限
     * @param summaryMapper 联系人最小展示映射器
     * @param auditEventPort 去标识化审计端口
     * @param clock UTC 时钟
     */
    public ContactVerificationService(
            ContactBindingRepositoryPort repositoryPort,
            SensitiveDataProtector sensitiveDataProtector,
            DigestService digestService,
            ContactVerificationProperties properties,
            ContactSummaryMapper summaryMapper,
            AuditEventPort auditEventPort,
            Clock clock) {
        this.repositoryPort = repositoryPort;
        this.sensitiveDataProtector = sensitiveDataProtector;
        this.digestService = digestService;
        this.properties = properties;
        this.summaryMapper = summaryMapper;
        this.auditEventPort = auditEventPort;
        this.clock = clock;
    }

    /**
     * 验证当前 owner 手机上已明确打开的微信联系人。
     *
     * @param ownerUserId 从已验证 JWT 派生的 owner UUID
     * @param publicContactId ct_ 前缀联系人编号
     * @param idempotencyKey 本次验证幂等键，不得记录日志
     * @param command 最小页面验证证据
     * @return 验证后的联系人最小展示结果
     * @throws BusinessException 当联系人不存在、状态/版本冲突、规则不支持或页面证据不足时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public ContactSummary verify(
            UUID ownerUserId,
            String publicContactId,
            String idempotencyKey,
            LocalVerificationCommand command) {
        UUID contactId = PublicIdCodec.parseContactId(publicContactId);
        byte[] idempotencyHash = digestService.sha256(idempotencyKey);
        byte[] requestHash = digestService.sha256(command.fingerprintInput());

        repositoryPort.lockOwner(ownerUserId);
        ContactBinding binding = repositoryPort.findByOwnerAndIdForUpdate(ownerUserId, contactId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        ContactSummary replay = replayResult(binding, idempotencyHash, requestHash);
        if (replay != null) {
            return replay;
        }

        validateStateAndVersion(binding, command.expectedContactVersion());
        Instant now = Instant.now(clock);
        validateEvidence(command, now);

        String stableLocator = command.stableLocator().strip();
        byte[] locatorHash = sensitiveDataProtector.subjectHmac(
                WechatLocatorPolicy.HMAC_DOMAIN + stableLocator);
        if (repositoryPort.existsOtherByOwnerAndLocatorHash(
                ownerUserId, contactId, locatorHash)) {
            throw new BusinessException(ErrorCode.WECHAT_PAGE_UNVERIFIED);
        }

        byte[] remarkCipher = StringUtils.hasText(command.currentRemark())
                ? sensitiveDataProtector.encrypt(command.currentRemark().strip()) : null;
        ContactBinding verified = new ContactBinding(
                binding.id(), binding.ownerUserId(), binding.contactSubjectHash(),
                binding.contactSubjectCipher(), sensitiveDataProtector.encrypt(stableLocator),
                locatorHash, remarkCipher, command.wechatVersion(), command.ruleVersion(),
                idempotencyHash, requestHash, null, null, now, binding.relationship(),
                binding.consentPolicyVersion(), binding.consentedAt(),
                ContactStatus.ACTIVE_NO_ALIAS, binding.createdBy(), binding.version(),
                binding.createdAt(), now, null);
        ContactBinding saved = repositoryPort.save(verified);
        auditEventPort.append(ownerUserId, "CONTACT_LOCAL_VERIFY", "SUCCESS", null, now);
        return summaryMapper.toSummary(saved);
    }

    private ContactSummary replayResult(
            ContactBinding binding,
            byte[] idempotencyHash,
            byte[] requestHash) {
        if (binding.verificationIdempotencyKeyHash() == null
                || !digestService.constantTimeEquals(
                        binding.verificationIdempotencyKeyHash(), idempotencyHash)) {
            return null;
        }
        if (binding.verificationRequestHash() == null
                || !digestService.constantTimeEquals(
                        binding.verificationRequestHash(), requestHash)) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        return summaryMapper.toSummary(binding);
    }

    private void validateStateAndVersion(ContactBinding binding, long expectedVersion) {
        if (binding.consentPolicyVersion() == null || binding.consentedAt() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (binding.status() != ContactStatus.PENDING_LOCAL_VERIFY
                && binding.status() != ContactStatus.REVERIFY_REQUIRED) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        if (expectedVersion != binding.version() + 1) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
    }

    private void validateEvidence(LocalVerificationCommand command, Instant now) {
        if (!properties.supports(command.wechatVersion(), command.ruleVersion())) {
            throw new BusinessException(ErrorCode.WECHAT_RULE_UNSUPPORTED);
        }
        Instant oldestAllowed = now.minus(properties.evidenceMaxAge());
        Instant newestAllowed = now.plus(properties.futureClockSkew());
        if (command.verifiedAt().isBefore(oldestAllowed)
                || command.verifiedAt().isAfter(newestAllowed)
                || command.pageType() == WechatPageType.UNSUPPORTED
                || !command.friendConfirmed()
                || command.locatorObservationCount() < REQUIRED_OBSERVATIONS
                || !command.locatorUnique()) {
            throw new BusinessException(ErrorCode.WECHAT_PAGE_UNVERIFIED);
        }
    }
}
