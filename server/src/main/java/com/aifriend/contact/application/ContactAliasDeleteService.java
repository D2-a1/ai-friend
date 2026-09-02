package com.aifriend.contact.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.contact.domain.ContactAlias;
import com.aifriend.contact.domain.ContactAliasStatus;
import com.aifriend.contact.domain.ContactBinding;
import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;

/**
 * 联系人称呼删除用例服务。
 *
 * <p>删除立即清空可解密称呼数据并排除匹配；最后一个有效称呼删除后，联系人转为
 * ACTIVE_NO_ALIAS。缓存和可重建投影通过同事务 Outbox 后续清理。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class ContactAliasDeleteService {

    private final AliasNamespaceRepositoryPort namespaceRepositoryPort;
    private final ContactBindingRepositoryPort bindingRepositoryPort;
    private final ContactAliasRepositoryPort aliasRepositoryPort;
    private final DigestService digestService;
    private final ContactSummaryMapper summaryMapper;
    private final ContactAliasOutboxPort outboxPort;
    private final AuditEventPort auditEventPort;
    private final Clock clock;

    /**
     * 创建联系人称呼删除服务。
     *
     * @param namespaceRepositoryPort owner 称呼命名空间锁端口
     * @param bindingRepositoryPort 联系人绑定端口
     * @param aliasRepositoryPort 联系人称呼端口
     * @param digestService 幂等与请求摘要服务
     * @param summaryMapper 联系人展示映射器
     * @param outboxPort 缓存与投影清理事件端口
     * @param auditEventPort 去标识化审计端口
     * @param clock UTC 时钟
     */
    public ContactAliasDeleteService(
            AliasNamespaceRepositoryPort namespaceRepositoryPort,
            ContactBindingRepositoryPort bindingRepositoryPort,
            ContactAliasRepositoryPort aliasRepositoryPort,
            DigestService digestService,
            ContactSummaryMapper summaryMapper,
            ContactAliasOutboxPort outboxPort,
            AuditEventPort auditEventPort,
            Clock clock) {
        this.namespaceRepositoryPort = namespaceRepositoryPort;
        this.bindingRepositoryPort = bindingRepositoryPort;
        this.aliasRepositoryPort = aliasRepositoryPort;
        this.digestService = digestService;
        this.summaryMapper = summaryMapper;
        this.outboxPort = outboxPort;
        this.auditEventPort = auditEventPort;
        this.clock = clock;
    }

    /**
     * 删除当前 owner 指定联系人的一个称呼。
     *
     * @param ownerUserId 已验证 JWT 派生的 owner UUID
     * @param publicContactId ct_ 前缀联系人编号
     * @param publicAliasId al_ 前缀称呼编号
     * @param idempotencyKey 删除幂等键，不得记录日志
     * @param command 删除确认与联系人版本
     * @return 删除后的联系人及剩余有效称呼
     * @throws BusinessException 未确认、资源越权、版本或幂等语义冲突时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public ContactSummary delete(
            UUID ownerUserId,
            String publicContactId,
            String publicAliasId,
            String idempotencyKey,
            DeleteContactAliasCommand command) {
        if (command == null || !command.confirmed()
                || command.expectedContactVersion() < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        UUID contactId = PublicIdCodec.parseContactId(publicContactId);
        UUID aliasId = PublicIdCodec.parseAliasId(publicAliasId);
        byte[] idempotencyHash = digestService.sha256(idempotencyKey);
        byte[] requestHash = digestService.sha256(command.fingerprintInput());

        long namespaceVersion = namespaceRepositoryPort.lock(ownerUserId);
        ContactBinding binding = bindingRepositoryPort
                .findByOwnerAndIdForUpdate(ownerUserId, contactId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        ContactAlias alias = aliasRepositoryPort.findByOwnerAndBindingAndIdForUpdate(
                ownerUserId, contactId, aliasId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        ContactSummary replay = replayResult(
                binding, alias, idempotencyHash, requestHash);
        if (replay != null) {
            return replay;
        }
        validateState(binding, alias, command.expectedContactVersion());

        Instant now = Instant.now(clock);
        aliasRepositoryPort.save(alias.delete(idempotencyHash, requestHash, now));
        boolean hasActiveAliases = aliasRepositoryPort.countActiveByBinding(
                ownerUserId, contactId) > 0;
        ContactBinding savedBinding = bindingRepositoryPort.save(
                binding.withAliasPresence(hasActiveAliases, now));
        namespaceRepositoryPort.increment(ownerUserId, namespaceVersion, now);
        outboxPort.appendDeleted(aliasId, contactId, ownerUserId, now);
        auditEventPort.append(ownerUserId, "CONTACT_ALIAS_DELETE", "SUCCESS", null, now);
        return summaryMapper.toSummary(
                savedBinding,
                visibleAliases(savedBinding, ownerUserId));
    }

    private ContactSummary replayResult(
            ContactBinding binding,
            ContactAlias alias,
            byte[] idempotencyHash,
            byte[] requestHash) {
        if (alias.deleteIdempotencyKeyHash() == null
                || !digestService.constantTimeEquals(
                        alias.deleteIdempotencyKeyHash(), idempotencyHash)) {
            return null;
        }
        if (alias.deleteRequestHash() == null
                || !digestService.constantTimeEquals(alias.deleteRequestHash(), requestHash)) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        return summaryMapper.toSummary(binding, visibleAliases(binding, binding.ownerUserId()));
    }

    private void validateState(
            ContactBinding binding,
            ContactAlias alias,
            long expectedContactVersion) {
        if (alias.status() != ContactAliasStatus.ACTIVE
                || (binding.status() != ContactStatus.ACTIVE
                        && binding.status() != ContactStatus.ACTIVE_NO_ALIAS)
                || binding.version() + 1 != expectedContactVersion) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
    }

    private List<ContactAlias> visibleAliases(ContactBinding binding, UUID ownerUserId) {
        if (binding.status() == ContactStatus.REVOKED) {
            return List.of();
        }
        return aliasRepositoryPort.findActiveByOwner(ownerUserId).stream()
                .filter(value -> value.bindingId().equals(binding.id()))
                .toList();
    }
}
