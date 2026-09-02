package com.aifriend.contact.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 联系人解绑后续清理事件 Outbox 端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface ContactCleanupOutboxPort {

    /**
     * 写入与解绑状态同事务提交的清理事件。
     *
     * @param contactId 联系人绑定 UUID
     * @param ownerUserId owner UUID
     * @param createdAt 事件创建时间
     */
    void appendUnbound(UUID contactId, UUID ownerUserId, Instant createdAt);
}
