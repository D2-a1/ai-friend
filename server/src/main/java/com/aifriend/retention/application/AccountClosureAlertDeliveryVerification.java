package com.aifriend.retention.application;

import java.util.Arrays;
import java.util.Objects;

/**
 * 外部通知通道对既有供应商流水号的最终状态复验。
 *
 * @param status 供应商状态
 * @param receiptProof 仅在 DELIVERED 时存在的稳定回执证明
 * @author Codex
 * @since 1.0.0
 */
public record AccountClosureAlertDeliveryVerification(
        AccountClosureAlertVerificationStatus status,
        byte[] receiptProof) {

    /**
     * 校验状态和回执证明必须成对出现。
     */
    public AccountClosureAlertDeliveryVerification {
        Objects.requireNonNull(status, "告警复验状态不能为空");
        if (status == AccountClosureAlertVerificationStatus.DELIVERED) {
            if (receiptProof == null
                    || receiptProof.length < 16
                    || receiptProof.length > 512) {
                throw new IllegalArgumentException("告警送达回执无效");
            }
            receiptProof = Arrays.copyOf(receiptProof, receiptProof.length);
        } else if (receiptProof != null) {
            throw new IllegalArgumentException("未送达状态不得携带回执");
        }
    }

    /**
     * 返回回执证明的防御性副本。
     *
     * @return 回执副本；未送达时为空
     */
    @Override
    public byte[] receiptProof() {
        return receiptProof == null ? null : Arrays.copyOf(receiptProof, receiptProof.length);
    }
}