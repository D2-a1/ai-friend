package com.aifriend.retention.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一项允许在数据库事务外执行的匿名注销告警投递。
 *
 * @param deliveryId 随机投递 UUID，也是告警正文中的稳定事件编号
 * @param audience 固定责任接收组
 * @param type 固定告警类型
 * @param occurredAt 告警事实发生时间
 * @param acknowledgementDueAt P0 接手确认截止时间；其他事件可为空
 * @param retryCount 已失败尝试次数
 * @param providerReference 已受理消息的供应商流水号；尚未提交时为空
 * @author Codex
 * @since 1.0.0
 */
public record AccountClosureAlertDelivery(
        UUID deliveryId,
        AccountClosureAlertAudience audience,
        AccountClosureAlertType type,
        Instant occurredAt,
        Instant acknowledgementDueAt,
        int retryCount,
        String providerReference) {

    /**
     * 创建尚未提交到外部通道的告警投递。
     *
     * @param deliveryId 随机投递 UUID
     * @param audience 固定责任接收组
     * @param type 固定告警类型
     * @param occurredAt 告警发生时间
     * @param acknowledgementDueAt P0 接手截止时间
     * @param retryCount 已失败尝试次数
     */
    public AccountClosureAlertDelivery(
            UUID deliveryId,
            AccountClosureAlertAudience audience,
            AccountClosureAlertType type,
            Instant occurredAt,
            Instant acknowledgementDueAt,
            int retryCount) {
        this(deliveryId, audience, type, occurredAt, acknowledgementDueAt, retryCount, null);
    }

    /**
     * 校验外发对象只包含有界告警事实和受限供应商流水号。
     */
    public AccountClosureAlertDelivery {
        Objects.requireNonNull(deliveryId, "告警投递 UUID 不能为空");
        Objects.requireNonNull(audience, "告警接收组不能为空");
        Objects.requireNonNull(type, "告警类型不能为空");
        Objects.requireNonNull(occurredAt, "告警发生时间不能为空");
        if (retryCount < 0) {
            throw new IllegalArgumentException("告警重试次数不能为负数");
        }
        if (type == AccountClosureAlertType.ACCOUNT_CLOSURE_P0_OPENED
                && acknowledgementDueAt == null) {
            throw new IllegalArgumentException("P0 告警必须携带接手确认截止时间");
        }
        if (type != AccountClosureAlertType.ACCOUNT_CLOSURE_P0_OPENED
                && acknowledgementDueAt != null) {
            throw new IllegalArgumentException("非 P0 告警不得携带接手确认截止时间");
        }
        if (!type.audiences().contains(audience)) {
            throw new IllegalArgumentException("告警类型与接收组不匹配");
        }
        if (providerReference != null
                && (providerReference.length() < 16
                    || providerReference.length() > 128
                    || !providerReference.chars().allMatch(character ->
                            character >= '0' && character <= '9'
                                    || character >= 'A' && character <= 'Z'
                                    || character >= 'a' && character <= 'z'))) {
            throw new IllegalArgumentException("告警供应商流水号格式无效");
        }
    }

    /**
     * 判断告警是否已经获得供应商受理流水号。
     *
     * @return 已提交时返回 true
     */
    public boolean hasProviderReference() {
        return providerReference != null;
    }
}