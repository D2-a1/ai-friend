package com.aifriend.shared.security;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import com.aifriend.identity.application.DeviceTrustService;
import com.aifriend.shared.api.ApiError;
import com.aifriend.shared.api.TraceContext;

/**
 * 拒绝已进入 DELETING/DELETED 的旧 access token 继续访问业务接口。
 *
 * <p>账号注销接口本身允许同一 access token 重放，以便同键同正文恢复 202 受理结果。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
public class ActiveAccountFilter extends OncePerRequestFilter {
    private final ActiveAccountStatusPort statusPort;
    private final DeviceTrustService deviceTrustService;
    private final ObjectMapper objectMapper;

    /**
     * 创建 ACTIVE 账号安全过滤器。
     *
     * @param statusPort 账号状态最小查询端口
     * @param deviceTrustService 当前设备白名单
     * @param objectMapper 统一 JSON 序列化器
     */
    public ActiveAccountFilter(
            ActiveAccountStatusPort statusPort,
            DeviceTrustService deviceTrustService,
            ObjectMapper objectMapper) {
        this.statusPort = statusPort;
        this.deviceTrustService = deviceTrustService;
        this.objectMapper = objectMapper;
    }

    /** {@inheritDoc} */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt
                && !isAccountClosureReplay(request)
                && !statusPort.isActive(CurrentUser.from(jwt).id())) {
            writeError(
                    response,
                    HttpServletResponse.SC_CONFLICT,
                    "ACCOUNT_CLOSURE_ACCEPTED",
                    "注销申请已经受理");
            return;
        }
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt
                && !deviceTrustService.isAllowedJwtDevice(
                        jwt.getClaimAsString("device_public_key_sha256"))) {
            writeError(
                    response,
                    HttpServletResponse.SC_FORBIDDEN,
                    "DEVICE_NOT_ALLOWED",
                    "这台手机尚未放行");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void writeError(
            HttpServletResponse response,
            int status,
            String code,
            String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(
                response.getOutputStream(),
                new ApiError(code, message, null, TraceContext.currentTraceId()));
    }

    private boolean isAccountClosureReplay(HttpServletRequest request) {
        return HttpMethod.DELETE.matches(request.getMethod())
                && "/me/account".equals(request.getRequestURI());
    }
}
