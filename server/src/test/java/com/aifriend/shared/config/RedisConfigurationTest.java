package com.aifriend.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Objects;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;

/**
 * Redis 部署配置绑定测试。
 *
 * <p>验证 Redis 密码只通过外部环境注入、OAuth 固定限流配置，并确保生产
 * 就绪探针包含应用状态、MySQL 和 Redis，且 Tomcat 原始访问日志保持关闭。
 *
 * @author Codex
 * @since 1.0.0
 */
class RedisConfigurationTest {

    private static final String SAMPLE_PASSWORD = "configuration-binding-only";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RedisBindingConfiguration.class);

    /**
     * 验证 Spring Boot 能绑定 Redis 连接参数和外部密码。
     */
    @Test
    void shouldBindRedisConnectionProperties() {
        contextRunner.withPropertyValues(
                "spring.data.redis.host=127.0.0.1",
                "spring.data.redis.port=6379",
                "spring.data.redis.password=" + SAMPLE_PASSWORD,
                "spring.data.redis.timeout=2s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RedisProperties properties = context.getBean(RedisProperties.class);
                    assertThat(properties.getHost()).isEqualTo("127.0.0.1");
                    assertThat(properties.getPort()).isEqualTo(6379);
                    assertThat(properties.getPassword()).isEqualTo(SAMPLE_PASSWORD);
                    assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(2));
                });
    }

    /**
     * 验证正式配置强制外部密码，并把 MySQL 与 Redis 纳入就绪探针。
     */
    @Test
    void shouldRequireExternalPasswordAndDependenciesForProductionReadiness() {
        Properties mainProperties = loadYaml("src/main/resources/application.yml");
        Properties productionProperties = loadYaml("src/main/resources/application-prod.yml");

        assertThat(mainProperties.getProperty("spring.data.redis.password"))
                .isEqualTo("${AI_FRIEND_REDIS_PASSWORD:}");
        assertThat(productionProperties.getProperty("spring.data.redis.password"))
                .isEqualTo("${AI_FRIEND_REDIS_PASSWORD}");
        assertThat(productionProperties.getProperty(
                "management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,db,redis");
        assertThat(mainProperties.getProperty(
                "ai-friend.invitation-oauth-rate-limit.maximum-attempts")).isEqualTo("5");
        assertThat(mainProperties.getProperty(
                "ai-friend.invitation-oauth-rate-limit.window")).isEqualTo("5m");
        assertThat(productionProperties.getProperty("server.tomcat.accesslog.enabled"))
                .isEqualTo("false");
    }

    private Properties loadYaml(String path) {
        YamlPropertiesFactoryBean yamlFactory = new YamlPropertiesFactoryBean();
        yamlFactory.setResources(new FileSystemResource(path));
        return Objects.requireNonNull(yamlFactory.getObject());
    }

    /**
     * 仅加载 Spring Boot Redis 类型安全配置的测试上下文。
     *
     * @author Codex
     * @since 1.0.0
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RedisProperties.class)
    static class RedisBindingConfiguration {
    }
}
