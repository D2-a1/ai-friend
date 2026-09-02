package com.aifriend.shared.security;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import com.aifriend.shared.api.ApiError;
import com.aifriend.shared.api.TraceContext;
import com.aifriend.identity.application.DeviceTrustService;

/**
 * 默认拒绝的 HTTP 安全骨架。
 *
 * @author Codex
 * @since 1.0.0
 */
@Configuration
public class SecurityConfiguration {

    /**
     * 创建默认拒绝的 HTTP 安全配置。
     */
    public SecurityConfiguration() {
    }

    /**
     * 配置无状态安全过滤链。
     *
     * @param http HTTP 安全配置
     * @param objectMapper 统一错误响应 JSON 序列化器
     * @param activeAccountStatusPort ACTIVE 账号状态端口
     * @param deviceTrustService 当前设备白名单
     * @return 安全过滤链
     * @throws Exception 配置失败时抛出
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            ActiveAccountStatusPort activeAccountStatusPort,
            DeviceTrustService deviceTrustService) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/auth/wechat/sessions", "/auth/tokens/refresh").permitAll()
                        .requestMatchers(HttpMethod.POST, "/public/contact-invitation-sessions").permitAll()
                        .requestMatchers(HttpMethod.GET, "/invite/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/dev/wechat-authorization").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/dev/audio-objects/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/oauth/wechat/invitation-callback").permitAll()
                        .requestMatchers(
                                HttpMethod.GET,
                                "/operations/account-closure-alert-deliveries/*/acknowledgement")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/operations/account-closure-alert-deliveries/*/acknowledgement").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/public/contact-invitation-sessions/current").permitAll()
                        .requestMatchers(HttpMethod.POST, "/public/contact-invitations/accept").permitAll()
                        .requestMatchers(HttpMethod.POST, "/public/contact-invitations/decline").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(response, objectMapper, HttpServletResponse.SC_FORBIDDEN,
                                        "FORBIDDEN", "当前账号无权执行此操作")))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> { })
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
                                        "AUTH_REQUIRED", "请重新登录")))
                .addFilterAfter(
                        new ActiveAccountFilter(
                                activeAccountStatusPort,
                                deviceTrustService,
                                objectMapper),
                        BearerTokenAuthenticationFilter.class)
                .build();
    }

    private void writeError(HttpServletResponse response, ObjectMapper objectMapper, int status,
            String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        ApiError error = new ApiError(code, message, null, TraceContext.currentTraceId());
        objectMapper.writeValue(response.getOutputStream(), error);
    }
}
