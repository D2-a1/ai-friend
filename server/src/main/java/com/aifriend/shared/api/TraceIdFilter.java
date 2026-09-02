package com.aifriend.shared.api;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为每个请求建立不含业务标识的随机 traceId。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 随机 traceId 的字节数。 */
    private static final int TRACE_BYTES = 16;

    /** 生成不可预测 traceId 的安全随机数实例。 */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 创建匿名请求链路过滤器。
     */
    public TraceIdFilter() {
    }

    /**
     * 建立并清理请求链路上下文。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param filterChain 过滤器链
     * @throws ServletException Servlet 处理失败
     * @throws IOException IO 处理失败
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        byte[] randomBytes = new byte[TRACE_BYTES];
        secureRandom.nextBytes(randomBytes);
        String traceId = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        MDC.put(TraceContext.TRACE_ID_KEY, traceId);
        response.setHeader("X-Trace-Id", traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TraceContext.TRACE_ID_KEY);
        }
    }
}
