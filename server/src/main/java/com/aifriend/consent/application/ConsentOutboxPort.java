package com.aifriend.consent.application;

import java.time.Instant;
import java.util.UUID;

import com.aifriend.consent.domain.ConsentType;

/**
 * 授权撤回清理事件 Outbox 端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface ConsentOutboxPort {

    /**
     * 写入与授权记录同事务提交的撤回事件。
     *
     * @param consentRecordId 授权记录 UUID
     * @param userId 用户 UUID
     * @param type 授权类型
     * @param createdAt 创建时间
     */
    void appendRevocation(UUID consentRecordId, UUID userId, ConsentType type, Instant createdAt);
}
