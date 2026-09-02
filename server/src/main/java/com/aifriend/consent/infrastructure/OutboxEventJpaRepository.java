package com.aifriend.consent.infrastructure;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Outbox 事件 Spring Data Repository。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, UUID> {
}
