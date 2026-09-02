package com.aifriend.audit.infrastructure;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.shared.security.DigestService;

/**
 * 去标识化安全审计 JPA 适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JpaAuditEventAdapter implements AuditEventPort {

    private static final long RETENTION_DAYS = 180;

    private final AuditEventJpaRepository repository;
    private final DigestService digestService;

    /**
     * 创建审计适配器。
     *
     * @param repository 审计 Repository
     * @param digestService 摘要服务
     */
    public JpaAuditEventAdapter(AuditEventJpaRepository repository, DigestService digestService) {
        this.repository = repository;
        this.digestService = digestService;
    }

    /**
     * 写入只含去标识主体和稳定代码的审计事件。
     *
     * @param actorUserId 用户 UUID
     * @param action 动作码
     * @param result 结果码
     * @param reasonCode 原因码
     * @param occurredAt 发生时间
     */
    @Override
    public void append(UUID actorUserId, String action, String result, String reasonCode, Instant occurredAt) {
        repository.save(new AuditEventEntity(
                UUID.randomUUID(),
                digestService.sha256(actorUserId.toString()),
                action,
                result,
                reasonCode,
                occurredAt,
                occurredAt.plus(RETENTION_DAYS, ChronoUnit.DAYS)));
    }
}
