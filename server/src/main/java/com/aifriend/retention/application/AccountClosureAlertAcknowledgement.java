package com.aifriend.retention.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 已持久化的注销 P0 告警唯一接手确认结果。
 *
 * @param acknowledgementId 接手确认随机 UUID
 * @param audience 实际接手责任组
 * @param timing 接手时效分类
 * @param acknowledgementDueAt 原 P0 接手截止时间
 * @param acknowledgedAt 实际接手时间
 * @author Codex
 * @since 1.0.0
 */
public record AccountClosureAlertAcknowledgement(
        UUID acknowledgementId,
        AccountClosureAlertAudience audience,
        AccountClosureAlertAcknowledgementTiming timing,
        Instant acknowledgementDueAt,
        Instant acknowledgedAt) {

    /**
     * 校验接手确认结果完整性。
     */
    public AccountClosureAlertAcknowledgement {
        Objects.requireNonNull(acknowledgementId, "接手确认 UUID 不能为空");
        Objects.requireNonNull(audience, "接手责任组不能为空");
        Objects.requireNonNull(timing, "接手时效不能为空");
        Objects.requireNonNull(acknowledgementDueAt, "接手截止时间不能为空");
        Objects.requireNonNull(acknowledgedAt, "接手时间不能为空");
    }
}
