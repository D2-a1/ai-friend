package com.aifriend.audit.infrastructure;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 审计事件 Spring Data Repository。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface AuditEventJpaRepository extends JpaRepository<AuditEventEntity, UUID> {
}
