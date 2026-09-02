package com.aifriend.consent.application;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.consent.domain.ConsentDecision;
import com.aifriend.consent.domain.ConsentRecord;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;

/**
 * 查询当前授权和追加授权决定的用例服务。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class ConsentService {

    private final ConsentRecordPort consentRecordPort;
    private final ConsentOutboxPort consentOutboxPort;
    private final ConsentRevocationCleanupPort consentRevocationCleanupPort;
    private final AuditEventPort auditEventPort;
    private final DigestService digestService;
    private final Clock clock;

    /**
     * 创建授权用例服务。
     *
     * @param consentRecordPort 授权记录端口
     * @param consentOutboxPort 授权撤回 Outbox 端口
     * @param consentRevocationCleanupPort 撤权后的同事务逻辑清理端口
     * @param auditEventPort 审计端口
     * @param digestService 摘要服务
     * @param clock UTC 时钟
     */
    public ConsentService(
            ConsentRecordPort consentRecordPort,
            ConsentOutboxPort consentOutboxPort,
            ConsentRevocationCleanupPort consentRevocationCleanupPort,
            AuditEventPort auditEventPort,
            DigestService digestService,
            Clock clock) {
        this.consentRecordPort = consentRecordPort;
        this.consentOutboxPort = consentOutboxPort;
        this.consentRevocationCleanupPort = consentRevocationCleanupPort;
        this.auditEventPort = auditEventPort;
        this.digestService = digestService;
        this.clock = clock;
    }

    /**
     * 查询用户各授权类型的最新决定。
     *
     * @param userId 当前用户 UUID
     * @return 最新授权记录，未作决定的类型不返回
     */
    @Transactional(readOnly = true)
    public List<ConsentRecord> listCurrent(UUID userId) {
        Map<ConsentType, ConsentRecord> latestByType = new EnumMap<>(ConsentType.class);
        consentRecordPort.listByUser(userId).forEach(record -> latestByType.putIfAbsent(record.type(), record));
        List<ConsentRecord> currentRecords = new ArrayList<>();
        for (ConsentType type : ConsentType.values()) {
            ConsentRecord record = latestByType.get(type);
            if (record != null) {
                currentRecords.add(record);
            }
        }
        return List.copyOf(currentRecords);
    }

    /**
     * 追加一条授权决定；相同幂等键和相同请求返回原记录，不同请求返回冲突。
     *
     * @param userId 当前用户 UUID
     * @param type 授权类型
     * @param decision 授权决定
     * @param policyVersion 政策版本
     * @param confirmedAt 客户端明确确认时间
     * @param idempotencyKey 幂等键原文，仅在本次调用内存中使用
     * @return 新记录或原幂等记录
     * @throws BusinessException 当同一幂等键已用于不同授权请求时抛出
     * @throws IllegalStateException 当数据库追加后无法读取授权记录时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public ConsentRecord update(
            UUID userId,
            ConsentType type,
            ConsentDecision decision,
            String policyVersion,
            Instant confirmedAt,
            String idempotencyKey) {
        byte[] idempotencyHash = digestService.sha256(idempotencyKey);
        byte[] requestHash = digestService.sha256(
                type.name() + "|" + decision.name() + "|" + policyVersion + "|" + confirmedAt);
        ConsentRecord candidate = new ConsentRecord(
                UUID.randomUUID(),
                userId,
                type,
                decision,
                policyVersion,
                confirmedAt,
                Instant.now(clock),
                idempotencyHash,
                requestHash);
        boolean inserted = consentRecordPort.append(candidate);
        ConsentRecord persisted = consentRecordPort
                .findByIdempotencyKey(userId, type, idempotencyHash)
                .orElseThrow(() -> new IllegalStateException("授权记录追加后未找到"));
        if (!MessageDigest.isEqual(persisted.requestHash(), requestHash)) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT, "幂等键已用于不同的授权请求");
        }
        if (inserted) {
            if (decision == ConsentDecision.REVOKED) {
                consentRevocationCleanupPort.cleanup(
                        userId, type, candidate.decidedAt());
                consentOutboxPort.appendRevocation(candidate.id(), userId, type, candidate.decidedAt());
            }
            auditEventPort.append(userId, "CONSENT_" + decision.name(), "SUCCESS", type.name(), candidate.decidedAt());
        }
        return persisted;
    }
}
