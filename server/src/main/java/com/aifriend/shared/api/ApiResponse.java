package com.aifriend.shared.api;

/**
 * OpenAPI 约定的统一成功响应。
 *
 * @param code 固定成功码 OK
 * @param message 成功摘要
 * @param data 业务响应数据
 * @param traceId 匿名链路标识
 * @param <T> 业务数据类型
 * @author Codex
 * @since 1.0.0
 */
public record ApiResponse<T>(
        String code,
        String message,
        T data,
        String traceId) {

    /**
     * 创建成功响应。
     *
     * @param data 业务数据
     * @param <T> 业务数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("OK", "success", data, TraceContext.currentTraceId());
    }
}
