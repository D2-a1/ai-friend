package com.aifriend.invitation.api;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 邀请 Web 页及其静态资源安全响应头过滤器。
 *
 * <p>页面只允许同源脚本、样式和请求，禁止被嵌入、禁止浏览器能力和缓存，
 * 防止邀请 proof、OAuth code/state 或页面内容经 Referer、第三方资源和历史缓存泄露。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class InvitationWebHeadersFilter extends OncePerRequestFilter {

    private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";
    private static final String PERMISSIONS_POLICY = "Permissions-Policy";
    private static final String REFERRER_POLICY = "Referrer-Policy";
    private static final String CSP_VALUE = "default-src 'none'; script-src 'self'; "
            + "style-src 'self'; connect-src 'self'; img-src 'self'; base-uri 'none'; "
            + "form-action 'self'; frame-ancestors 'none'";

    /**
     * 创建邀请页面安全响应头过滤器。
     */
    public InvitationWebHeadersFilter() {
    }

    /**
     * 为邀请页面、OAuth 回调和开发态授权页写入固定安全响应头。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param filterChain 后续过滤器链
     * @throws ServletException Servlet 处理失败
     * @throws IOException IO 处理失败
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        response.setHeader(CONTENT_SECURITY_POLICY, CSP_VALUE);
        response.setHeader(PERMISSIONS_POLICY,
                "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
        response.setHeader(REFERRER_POLICY, "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        filterChain.doFilter(request, response);
    }

    /**
     * 仅处理邀请页面、同源资源和开发态授权页。
     *
     * @param request HTTP 请求
     * @return 不属于邀请 Web 资源时返回 true
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = requestUri.startsWith(contextPath)
                ? requestUri.substring(contextPath.length()) : requestUri;
        return !path.startsWith("/invite/")
                && !"/oauth/wechat/invitation-callback".equals(path)
                && !"/dev/wechat-authorization".equals(path);
    }
}
