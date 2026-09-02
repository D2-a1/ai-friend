package com.aifriend.retention.application;

/**
 * 注销 P0 告警接手时效分类。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum AccountClosureAlertAcknowledgementTiming {
    /** 严格早于接手截止时间。 */
    TIMELY,
    /** 到达或晚于接手截止时间，不能撤销升级事实。 */
    LATE
}
