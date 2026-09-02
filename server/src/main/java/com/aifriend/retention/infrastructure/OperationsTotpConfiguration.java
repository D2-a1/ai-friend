package com.aifriend.retention.infrastructure;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.aifriend.retention.application.AccountClosureAlertResponderIdentityPort;
import com.aifriend.retention.application.OperationsTotpProperties;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.SensitiveDataProtector;

/**
 * 生产个人运维 TOTP 身份适配器条件装配。
 *
 * @author Codex
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class OperationsTotpConfiguration {

    /**
     * 创建生产个人运维身份条件配置。
     */
    public OperationsTotpConfiguration() {
    }

    /**
     * 提供开关开启后的个人 TOTP 运维身份适配器。
     *
     * @param properties 个人运维身份配置
     * @param redisTemplate Redis 字符串操作模板
     * @param sensitiveDataProtector 主体域隔离 HMAC 组件
     * @param digestService SHA-256 摘要组件
     * @param clock UTC 时钟
     * @return 独立运维身份验证端口
     */
    @Bean
    @Primary
    @ConditionalOnProperty(
            prefix = "ai-friend.retention.operations-totp",
            name = "enabled",
            havingValue = "true")
    public AccountClosureAlertResponderIdentityPort
            totpAccountClosureAlertResponderIdentityPort(
                    OperationsTotpProperties properties,
                    StringRedisTemplate redisTemplate,
                    SensitiveDataProtector sensitiveDataProtector,
                    DigestService digestService,
                    Clock clock) {
        RedisOperationsTotpGuard guard = new RedisOperationsTotpGuard(
                redisTemplate,
                digestService);
        return new TotpAccountClosureAlertResponderIdentityAdapter(
                properties,
                guard,
                sensitiveDataProtector,
                digestService,
                clock);
    }
}
