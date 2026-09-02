package com.aifriend.identity.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import com.aifriend.identity.application.WechatIdentityPort;
import com.aifriend.identity.application.WechatIdentityProperties;

/**
 * 生产微信移动应用身份适配器条件装配。
 *
 * <p>仅 prod profile 参与装配。开关关闭或缺省时继续使用失败关闭适配器；
 * 开关开启时配置属性先校验 AppID、AppSecret、固定官方端点和超时，再将真实适配器
 * 标记为首选端口。失败关闭 Bean 仍保留，确保生产开关回退后无需改变装配结构。
 *
 * @author Codex
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class WechatIdentityConfiguration {

    /**
     * 创建生产微信身份条件配置。
     */
    public WechatIdentityConfiguration() {
    }

    /**
     * 提供开关开启后的真实微信身份适配器。
     *
     * @param properties 微信身份配置
     * @param restClientBuilder Spring HTTP 客户端构造器
     * @param objectMapper JSON 解析器
     * @param circuitBreakerRegistry 熔断器注册表
     * @param bulkheadRegistry 舱壁注册表
     * @return 真实微信身份端口
     */
    @Bean
    @Primary
    @ConditionalOnProperty(
            prefix = "ai-friend.wechat.identity",
            name = "enabled",
            havingValue = "true")
    public WechatIdentityPort wechatIdentityPort(
            WechatIdentityProperties properties,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            CircuitBreakerRegistry circuitBreakerRegistry,
            BulkheadRegistry bulkheadRegistry) {
        return new WechatIdentityAdapter(
                properties,
                restClientBuilder,
                objectMapper,
                circuitBreakerRegistry,
                bulkheadRegistry);
    }
}
