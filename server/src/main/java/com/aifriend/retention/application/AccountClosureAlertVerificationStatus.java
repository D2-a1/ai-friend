package com.aifriend.retention.application;

/**
 * 外部通知供应商报告的告警投递状态。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum AccountClosureAlertVerificationStatus {
    /** 供应商仍未完成处理。 */
    PENDING,
    /** 供应商明确确认已经送达。 */
    DELIVERED,
    /** 供应商明确确认发送失败，可重新提交。 */
    FAILED
}
