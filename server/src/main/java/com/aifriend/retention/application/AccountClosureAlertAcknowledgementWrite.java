package com.aifriend.retention.application;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * 写入注销 P0 告警接手确认的最小命令。
 *
 * @param deliveryId 已投递 P0 告警 UUID
 * @param audience 已验证接手责任组
 * @param responderSubjectHash 运维主体域隔离 HMAC-SHA-256
 * @param authenticationContextHash 认证上下文不可逆摘要
 * @param idempotencyKeyHash 幂等键 SHA-256
 * @param requestHash 完整请求语义 SHA-256
 * @param acknowledgedAt 当前 UTC 接手时间
 * @author Codex
 * @since 1.0.0
 */
public record AccountClosureAlertAcknowledgementWrite(
        UUID deliveryId,
        AccountClosureAlertAudience audience,
        byte[] responderSubjectHash,
        byte[] authenticationContextHash,
        byte[] idempotencyKeyHash,
        byte[] requestHash,
        Instant acknowledgedAt) {

    /**
     * 校验写入命令并复制全部摘要。
     */
    public AccountClosureAlertAcknowledgementWrite {
        Objects.requireNonNull(deliveryId, "告警投递 UUID 不能为空");
        Objects.requireNonNull(audience, "接手责任组不能为空");
        Objects.requireNonNull(acknowledgedAt, "接手时间不能为空");
        responderSubjectHash = copyDigest(responderSubjectHash, "接手主体摘要无效");
        authenticationContextHash = copyDigest(
                authenticationContextHash,
                "认证上下文摘要无效");
        idempotencyKeyHash = copyDigest(idempotencyKeyHash, "接手幂等摘要无效");
        requestHash = copyDigest(requestHash, "接手请求摘要无效");
    }

    /**
     * 返回接手主体摘要副本。
     *
     * @return 32 字节摘要副本
     */
    @Override
    public byte[] responderSubjectHash() {
        return Arrays.copyOf(responderSubjectHash, responderSubjectHash.length);
    }

    /**
     * 返回认证上下文摘要副本。
     *
     * @return 32 字节摘要副本
     */
    @Override
    public byte[] authenticationContextHash() {
        return Arrays.copyOf(authenticationContextHash, authenticationContextHash.length);
    }

    /**
     * 返回幂等键摘要副本。
     *
     * @return 32 字节摘要副本
     */
    @Override
    public byte[] idempotencyKeyHash() {
        return Arrays.copyOf(idempotencyKeyHash, idempotencyKeyHash.length);
    }

    /**
     * 返回请求摘要副本。
     *
     * @return 32 字节摘要副本
     */
    @Override
    public byte[] requestHash() {
        return Arrays.copyOf(requestHash, requestHash.length);
    }

    private static byte[] copyDigest(byte[] digest, String message) {
        if (digest == null || digest.length != 32) {
            throw new IllegalArgumentException(message);
        }
        return Arrays.copyOf(digest, digest.length);
    }
}
