package com.aifriend.retention.application;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * 独立运维身份提供方验证后的最小接手身份事实。
 *
 * <p>只保留不可逆主体摘要、认证上下文摘要和责任组，不携带姓名、地址或凭据原文。</p>
 *
 * @param audience 已验证责任组
 * @param responderSubjectHash 运维身份提供方生成的主体域隔离 HMAC-SHA-256
 * @param authenticationContextHash 本次认证上下文 SHA-256 摘要
 * @param verifiedAt 身份验证时间
 * @param expiresAt 本次验证事实失效时间
 * @author Codex
 * @since 1.0.0
 */
public record AccountClosureAlertResponderIdentity(
        AccountClosureAlertAudience audience,
        byte[] responderSubjectHash,
        byte[] authenticationContextHash,
        Instant verifiedAt,
        Instant expiresAt) {

    /**
     * 校验最小身份事实并复制摘要。
     */
    public AccountClosureAlertResponderIdentity {
        Objects.requireNonNull(audience, "接手责任组不能为空");
        Objects.requireNonNull(verifiedAt, "身份验证时间不能为空");
        Objects.requireNonNull(expiresAt, "身份验证失效时间不能为空");
        responderSubjectHash = copyDigest(responderSubjectHash, "接手主体摘要无效");
        authenticationContextHash = copyDigest(
                authenticationContextHash,
                "认证上下文摘要无效");
    }

    /**
     * 返回接手主体摘要副本。
     *
     * @return 32 字节主体摘要副本
     */
    @Override
    public byte[] responderSubjectHash() {
        return Arrays.copyOf(responderSubjectHash, responderSubjectHash.length);
    }

    /**
     * 返回认证上下文摘要副本。
     *
     * @return 32 字节认证上下文摘要副本
     */
    @Override
    public byte[] authenticationContextHash() {
        return Arrays.copyOf(authenticationContextHash, authenticationContextHash.length);
    }

    private static byte[] copyDigest(byte[] digest, String message) {
        if (digest == null || digest.length != 32) {
            throw new IllegalArgumentException(message);
        }
        return Arrays.copyOf(digest, digest.length);
    }
}
