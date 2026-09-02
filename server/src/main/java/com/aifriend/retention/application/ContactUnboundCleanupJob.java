package com.aifriend.retention.application;

import java.util.UUID;

/**
 * 已锁定的联系人解绑清理任务。
 *
 * @param eventId Outbox 事件 UUID
 * @param contactId 已解绑联系人 UUID
 * @param ownerUserId 从联系人绑定反查的 owner UUID
 * @author Codex
 * @since 1.0.0
 */
public record ContactUnboundCleanupJob(
        UUID eventId,
        UUID contactId,
        UUID ownerUserId) {
}
