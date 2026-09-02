package com.aifriend.contact.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;

/**
 * 解除联系人绑定请求。
 *
 * @param confirmed 用户是否已完成二次确认，只允许 true
 * @param expectedContactVersion 客户端当前展示的联系人对外版本
 * @author Codex
 * @since 1.0.0
 */
public record ContactUnbindReq(
        @AssertTrue boolean confirmed,
        @Min(1) long expectedContactVersion) {
}
