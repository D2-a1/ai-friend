package com.aifriend.invitation.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import com.aifriend.invitation.application.WechatInvitationAuthorizationPort;
import com.aifriend.invitation.application.WechatInvitationIdentityPort;
import com.aifriend.invitation.application.WechatInvitationOAuthProperties;

/**
 * 生产微信邀请网页 OAuth 条件装配。
 *
 * <p>仅 prod profile 且生产开关明确开启时注册首选真实授权和身份端口；
 * 默认继续使用既有失败关闭适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class WechatInvitationOAuthConfiguration {

    /**
     * 创建生产邀请网页 OAuth 条件配置。
     */
    public WechatInvitationOAuthConfiguration() {
    }

    /**
     * 提供开关开启后的微信官方授权入口端口。
     *
     * @param properties 邀请网页 OAuth 配置
     * @return 真实微信授权入口端口
     */
    @Bean
    @Primary
    @ConditionalOnProperty(
            prefix = "ai-friend.wechat.invitation-oauth",
            name = "enabled",
            havingValue = "true")
    public WechatInvitationAuthorizationPort wechatInvitationAuthorizationPort(
            WechatInvitationOAuthProperties properties) {
        return new WechatInvitationAuthorizationAdapter(properties);
    }

    /**
     * 提供开关开启后的微信邀请身份兑换端口。
     *
     * @param properties 邀请网页 OAuth 配置
     * @param restClientBuilder Spring HTTP 客户端构造器
     * @param objectMapper JSON 解析器
     * @param circuitBreakerRegistry 熔断器注册表
     * @param bulkheadRegistry 舱壁注册表
     * @return 真实微信邀请身份端口
     */
    @Bean
    @Primary
    @ConditionalOnProperty(
            prefix = "ai-friend.wechat.invitation-oauth",
            name = "enabled",
            havingValue = "true")
    public WechatInvitationIdentityPort wechatInvitationIdentityPort(
            WechatInvitationOAuthProperties properties,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            CircuitBreakerRegistry circuitBreakerRegistry,
            BulkheadRegistry bulkheadRegistry) {
        return new WechatInvitationIdentityAdapter(
                properties,
                restClientBuilder,
                objectMapper,
                circuitBreakerRegistry,
                bulkheadRegistry);
    }
}
