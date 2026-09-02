package com.aifriend.template.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 安全指令整批注册幂等快照。
 *
 * @param id 注册批次 UUID
 * @param ownerUserId owner UUID
 * @param idempotencyKeyHash 幂等键 SHA-256 摘要
 * @param requestHash 请求语义 SHA-256 摘要
 * @param consentPolicyVersion 精确匹配的语音模板政策版本
 * @param completedAt 整批注册完成时间
 * @author Codex
 * @since 1.0.0
 */
public record SafetyCommandEnrollment(
        UUID id,
        UUID ownerUserId,
        byte[] idempotencyKeyHash,
        byte[] requestHash,
        String consentPolicyVersion,
        Instant completedAt) {

    /** 创建带防御性摘要副本的注册快照。 */
    public SafetyCommandEnrollment {
        idempotencyKeyHash = idempotencyKeyHash.clone();
        requestHash = requestHash.clone();
    }

    /**
     * 获取幂等键摘要的防御性副本。
     *
     * @return 幂等键摘要副本
     */
    @Override
    public byte[] idempotencyKeyHash() {
        return idempotencyKeyHash.clone();
    }

    /**
     * 获取请求语义摘要的防御性副本。
     *
     * @return 请求语义摘要副本
     */
    @Override
    public byte[] requestHash() {
        return requestHash.clone();
    }
}
