package com.aifriend.consent.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 追加式授权记录。
 *
 * @param id 记录 UUID
 * @param userId 用户 UUID
 * @param type 授权类型
 * @param decision 授权决定
 * @param policyVersion 政策版本
 * @param confirmedAt 客户端明确确认时间
 * @param decidedAt 服务端落库时间
 * @param idempotencyKeyHash 幂等键摘要
 * @param requestHash 请求语义摘要
 * @author Codex
 * @since 1.0.0
 */
public record ConsentRecord(
        UUID id,
        UUID userId,
        ConsentType type,
        ConsentDecision decision,
        String policyVersion,
        Instant confirmedAt,
        Instant decidedAt,
        byte[] idempotencyKeyHash,
        byte[] requestHash) {
}
