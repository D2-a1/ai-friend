package com.aifriend.personalization.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;

/**
 * 删除长期个人偏好的明确确认请求。
 *
 * @param confirmed 必须明确为 true
 * @param expectedVersion 当前资源版本
 * @author Codex
 * @since 1.0.0
 */
public record DeletePersonalMemoryReq(
        @AssertTrue boolean confirmed,
        @Positive long expectedVersion) {
}
