package com.aifriend.retention.infrastructure;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import com.aifriend.retention.application.AccountClosureAlertDeliveryPort;
import com.aifriend.retention.application.PushPlusAlertProperties;
import com.aifriend.retention.application.OperationsTotpProperties;

/**
 * 生产 PushPlus App 注销告警适配器条件装配。
 *
 * @author Codex
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class PushPlusAlertConfiguration {

    /**
     * 创建生产 PushPlus 告警条件配置。
     */
    public PushPlusAlertConfiguration() {
    }

    /**
     * 提供开关开启后的真实 PushPlus App 告警适配器。
     *
     * @param properties PushPlus 个人告警配置
     * @param operationsTotpProperties 个人运维接手页面配置
     * @param restClientBuilder Spring HTTP 客户端构造器
     * @param objectMapper 受限 JSON 解析器
     * @param circuitBreakerRegistry 熔断器注册表
     * @param bulkheadRegistry 舱壁注册表
     * @param clock UTC 时钟
     * @return 真实匿名告警通知端口
     */
    @Bean
    @Primary
    @ConditionalOnProperty(
            prefix = "ai-friend.retention.pushplus-alert",
            name = "enabled",
            havingValue = "true")
    public AccountClosureAlertDeliveryPort pushPlusAccountClosureAlertDeliveryPort(
            PushPlusAlertProperties properties,
            OperationsTotpProperties operationsTotpProperties,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            CircuitBreakerRegistry circuitBreakerRegistry,
            BulkheadRegistry bulkheadRegistry,
            Clock clock) {
        return new PushPlusAccountClosureAlertDeliveryAdapter(
                properties,
                operationsTotpProperties,
                restClientBuilder,
                objectMapper,
                circuitBreakerRegistry,
                bulkheadRegistry,
                clock);
    }
}
