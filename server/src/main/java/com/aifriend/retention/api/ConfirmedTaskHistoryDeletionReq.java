package com.aifriend.retention.api;

import jakarta.validation.constraints.AssertTrue;

/**
 * 任务历史清除明确确认请求。
 *
 * @param confirmed 必须为 true
 * @author Codex
 * @since 1.0.0
 */
public record ConfirmedTaskHistoryDeletionReq(@AssertTrue boolean confirmed) {
}