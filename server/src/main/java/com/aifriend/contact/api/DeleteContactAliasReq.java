package com.aifriend.contact.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;

/**
 * 联系人方言称呼删除请求。
 *
 * @param confirmed 用户已完成删除二次确认，只允许 true
 * @param expectedContactVersion 当前联系人对外版本
 * @author Codex
 * @since 1.0.0
 */
public record DeleteContactAliasReq(
        @AssertTrue boolean confirmed,
        @Min(1) long expectedContactVersion) {
}
