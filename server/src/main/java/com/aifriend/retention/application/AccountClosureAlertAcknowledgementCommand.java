package com.aifriend.retention.application;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * 注销 P0 告警显式接手确认命令。
 *
 * <p>运维凭据只允许驻留当前调用内存，不得写入数据库、日志或审计正文。</p>
 *
 * @param deliveryId 已取得稳定通知回执的 P0 投递 UUID
 * @param idempotencyKey 16 至 128 字符幂等键
 * @param credentialProof 独立运维身份提供方的一次性凭据
 * @author Codex
 * @since 1.0.0
 */
public record AccountClosureAlertAcknowledgementCommand(
        UUID deliveryId,
        String idempotencyKey,
        byte[] credentialProof) {

    /**
     * 复制可变凭据，避免调用方在验证期间改写内容。
     */
    public AccountClosureAlertAcknowledgementCommand {
        Objects.requireNonNull(deliveryId, "告警投递 UUID 不能为空");
        credentialProof = credentialProof == null
                ? null
                : Arrays.copyOf(credentialProof, credentialProof.length);
    }

    /**
     * 返回当前命令凭据副本。
     *
     * @return 一次性运维身份凭据副本
     */
    @Override
    public byte[] credentialProof() {
        return credentialProof == null
                ? null
                : Arrays.copyOf(credentialProof, credentialProof.length);
    }
}
