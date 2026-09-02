package com.aifriend.shared.api;

/**
 * 统一错误响应。
 *
 * @param code 稳定错误码
 * @param message 用户可理解的错误摘要
 * @param data 受控错误数据，默认空
 * @param traceId 匿名链路标识
 * @author Codex
 * @since 1.0.0
 */
public record ApiError(
        String code,
        String message,
        Object data,
        String traceId) {
}
