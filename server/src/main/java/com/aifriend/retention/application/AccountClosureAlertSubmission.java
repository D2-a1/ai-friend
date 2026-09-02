package com.aifriend.retention.application;

/**
 * 外部通知通道接受告警后的最小提交事实。
 *
 * @param providerReference 供应商返回的稳定消息流水号
 * @author Codex
 * @since 1.0.0
 */
public record AccountClosureAlertSubmission(String providerReference) {

    /**
     * 校验供应商流水号只包含受限 ASCII 字母和数字。
     */
    public AccountClosureAlertSubmission {
        if (providerReference == null
                || providerReference.length() < 16
                || providerReference.length() > 128
                || !providerReference.chars().allMatch(character ->
                        character >= '0' && character <= '9'
                                || character >= 'A' && character <= 'Z'
                                || character >= 'a' && character <= 'z')) {
            throw new IllegalArgumentException("告警供应商流水号格式无效");
        }
    }
}
