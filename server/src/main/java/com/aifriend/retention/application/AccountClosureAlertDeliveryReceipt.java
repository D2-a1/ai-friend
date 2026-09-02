package com.aifriend.retention.application;

import java.util.Arrays;

/**
 * 外部通知通道返回的有界可验证投递回执。
 *
 * <p>回执原文只在当前调用内存短暂存在，数据库仅保存其 SHA-256。</p>
 *
 * @param receiptProof 外部通道返回的稳定回执证明
 * @author Codex
 * @since 1.0.0
 */
public record AccountClosureAlertDeliveryReceipt(byte[] receiptProof) {

    /**
     * 校验回执边界并隔离可变数组。
     */
    public AccountClosureAlertDeliveryReceipt {
        if (receiptProof == null
                || receiptProof.length < 16
                || receiptProof.length > 512) {
            throw new IllegalArgumentException("注销告警投递回执无效");
        }
        receiptProof = Arrays.copyOf(receiptProof, receiptProof.length);
    }

    /**
     * 返回投递回执的防御性副本。
     *
     * @return 回执证明副本
     */
    @Override
    public byte[] receiptProof() {
        return Arrays.copyOf(receiptProof, receiptProof.length);
    }
}
