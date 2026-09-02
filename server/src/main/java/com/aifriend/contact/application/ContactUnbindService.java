package com.aifriend.contact.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.contact.domain.ContactBinding;
import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;

/**
 * 解除联系人绑定用例服务。
 *
 * <p>固定按 owner→联系人顺序加锁，验证二次确认、对外版本与幂等指纹。
 * 解绑与敏感定位失效、后续清理 Outbox 事件在同一数据库事务中提交。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class ContactUnbindService {

    private final ContactBindingRepositoryPort repositoryPort;
    private final ContactCleanupOutboxPort cleanupOutboxPort;
    private final DigestService digestService;
    private final ContactSummaryMapper summaryMapper;
    private final AuditEventPort auditEventPort;
    private final Clock clock;

    /**
     * 创建联系人解绑服务。
     *
     * @param repositoryPort owner 范围联系人持久化端口
     * @param cleanupOutboxPort 后续清理事件端口
     * @param digestService 幂等键与请求指纹摘要服务
     * @param summaryMapper 联系人最小展示映射器
     * @param auditEventPort 去标识化审计端口
     * @param clock UTC 时钟
     */
    public ContactUnbindService(
            ContactBindingRepositoryPort repositoryPort,
            ContactCleanupOutboxPort cleanupOutboxPort,
            DigestService digestService,
            ContactSummaryMapper summaryMapper,
            AuditEventPort auditEventPort,
            Clock clock) {
        this.repositoryPort = repositoryPort;
        this.cleanupOutboxPort = cleanupOutboxPort;
        this.digestService = digestService;
        this.summaryMapper = summaryMapper;
        this.auditEventPort = auditEventPort;
        this.clock = clock;
    }

    /**
     * 解除当前 owner 的联系人绑定。
     *
     * @param ownerUserId 从已验证 JWT 派生的 owner UUID
     * @param publicContactId ct_ 前缀联系人编号
     * @param idempotencyKey 本次解绑幂等键，不得记录日志
     * @param command 二次确认与版本命令
     * @return 解绑后的最小联系人结果
     * @throws BusinessException 当联系人不存在、未确认、版本冲突或幂等冲突时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public ContactSummary unbind(
            UUID ownerUserId,
            String publicContactId,
            String idempotencyKey,
            ContactUnbindCommand command) {
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
        validateCommand(binding, command);

        Instant now = Instant.now(clock);
        ContactBinding revoked = new ContactBinding(
                binding.id(), binding.ownerUserId(), binding.contactSubjectHash(), null,
                null, null, null, null, null,
                null, null, idempotencyHash, requestHash, null,
                null, binding.consentPolicyVersion(), binding.consentedAt(),
                ContactStatus.REVOKED, binding.createdBy(), binding.version(),
                binding.createdAt(), now, now);
        ContactBinding saved = repositoryPort.save(revoked);
        cleanupOutboxPort.appendUnbound(contactId, ownerUserId, now);
        auditEventPort.append(ownerUserId, "CONTACT_UNBIND", "SUCCESS", null, now);
        return summaryMapper.toSummary(saved);
    }

    private ContactSummary replayResult(
            ContactBinding binding,
            byte[] idempotencyHash,
            byte[] requestHash) {
        if (binding.unbindIdempotencyKeyHash() == null
                || !digestService.constantTimeEquals(
                        binding.unbindIdempotencyKeyHash(), idempotencyHash)) {
            return null;
        }
        if (binding.unbindRequestHash() == null
                || !digestService.constantTimeEquals(binding.unbindRequestHash(), requestHash)) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        return summaryMapper.toSummary(binding);
    }

    private void validateCommand(ContactBinding binding, ContactUnbindCommand command) {
        if (!command.confirmed()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (binding.status() == ContactStatus.REVOKED
                || command.expectedContactVersion() != binding.version() + 1) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
    }
}
