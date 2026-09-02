package com.aifriend.template.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 安全指令本地投影与缓存刷新 Outbox 端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface SafetyCommandOutboxPort {

    /**
     * 写入整批替换事件，不包含模板、音频或幂等摘要。
     *
     * @param enrollmentId 注册批次 UUID
     * @param ownerUserId owner UUID
     * @param occurredAt 事件时间
     */
    void appendReplaced(UUID enrollmentId, UUID ownerUserId, Instant occurredAt);
}
