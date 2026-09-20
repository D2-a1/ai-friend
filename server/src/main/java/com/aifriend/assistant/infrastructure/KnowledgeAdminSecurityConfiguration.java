package com.aifriend.assistant.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import com.aifriend.identity.application.DeviceTrustService;
import com.aifriend.shared.api.ApiError;
import com.aifriend.shared.api.TraceContext;
import com.aifriend.shared.security.ActiveAccountFilter;
import com.aifriend.shared.security.ActiveAccountStatusPort;

/**
 * 知识管理独立安全链，不向普通登录令牌增加管理 scope。
 * @author codex
 * @since 1.0.0
 */
@Configuration
public class KnowledgeAdminSecurityConfiguration {
    /** JWT 经标准 scope 转换后要求的确切管理权限。 */
    public static final String AUTHORITY = "SCOPE_knowledge:manage";

    /** 创建独立管理安全配置。 */
    public KnowledgeAdminSecurityConfiguration() {
    }

    /**
     * 管理接口仍复验账号和设备；默认无令牌持有管理 scope。
     * @param http Spring 安全构建器
     * @param mapper 错误信封序列化器
     * @param accounts 当前账号状态
     * @param devices 当前设备准入
     * @return 仅匹配知识管理路径的安全链
     * @throws Exception 配置失败时抛出
     */
    @Bean
    @Order(1)
    public SecurityFilterChain knowledgeAdminSecurityFilterChain(
            HttpSecurity http, ObjectMapper mapper,
            ActiveAccountStatusPort accounts, DeviceTrustService devices) throws Exception {
        return http.securityMatcher("/admin/knowledge/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().hasAuthority(AUTHORITY))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(403);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    mapper.writeValue(response.getOutputStream(),
                            new ApiError("FORBIDDEN", "当前账号无知识管理权限", null, TraceContext.currentTraceId()));
                }))
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> { })
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType("application/json");
                            response.setCharacterEncoding("UTF-8");
                            mapper.writeValue(response.getOutputStream(),
                                    new ApiError("AUTH_REQUIRED", "请重新登录", null, TraceContext.currentTraceId()));
                        }))
                .addFilterAfter(new ActiveAccountFilter(accounts, devices, mapper),
                        BearerTokenAuthenticationFilter.class)
                .build();
    }
}
