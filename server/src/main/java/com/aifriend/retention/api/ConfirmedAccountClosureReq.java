package com.aifriend.retention.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;

/**
 * 账号永久注销明确确认请求。
 *
 * @param confirmed 必须为 true
 * @param expectedVersion 可选的当前账号对外版本
 * @author Codex
 * @since 1.0.0
 */
public record ConfirmedAccountClosureReq(
        @AssertTrue boolean confirmed,
        @Min(1) Long expectedVersion) {
}
