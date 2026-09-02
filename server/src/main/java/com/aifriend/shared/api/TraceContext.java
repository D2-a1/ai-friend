package com.aifriend.shared.api;

import java.util.UUID;

import org.slf4j.MDC;

/**
 * 匿名请求链路标识访问器。
 *
 * @author Codex
 * @since 1.0.0
 */
public final class TraceContext {

    /**
     * MDC 中的链路标识键。
     */
    public static final String TRACE_ID_KEY = "traceId";

    private TraceContext() {
    }

    /**
     * 获取当前链路标识；非 HTTP 场景缺失时生成临时标识。
     *
     * @return 非空链路标识
     */
    public static String currentTraceId() {
        String traceId = MDC.get(TRACE_ID_KEY);
        return traceId == null || traceId.isBlank()
                ? UUID.randomUUID().toString().replace("-", "")
                : traceId;
    }
}
