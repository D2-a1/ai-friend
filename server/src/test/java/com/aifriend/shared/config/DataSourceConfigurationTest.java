package com.aifriend.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;

import com.zaxxer.hikari.HikariDataSource;

/**
 * 数据库连接池主配置绑定测试。
 *
 * <p>直接绑定主配置中的 Hikari 属性，防止单元测试 profile 跳过真实启动配置后，
 * 才在部署环境暴露连接超时字段类型错误。
 *
 * @author Codex
 * @since 1.0.0
 */
class DataSourceConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(HikariBindingConfiguration.class);

    @Test
    void shouldBindHikariTimeoutsAsMilliseconds() {
        YamlPropertiesFactoryBean yamlFactory = new YamlPropertiesFactoryBean();
        yamlFactory.setResources(new FileSystemResource("src/main/resources/application.yml"));
        Properties applicationProperties = Objects.requireNonNull(yamlFactory.getObject());
        String connectionTimeout = applicationProperties.getProperty(
                "spring.datasource.hikari.connection-timeout");
        String leakDetectionThreshold = applicationProperties.getProperty(
                "spring.datasource.hikari.leak-detection-threshold");

        contextRunner.withPropertyValues(
                "spring.datasource.hikari.connection-timeout=" + connectionTimeout,
                "spring.datasource.hikari.leak-detection-threshold=" + leakDetectionThreshold)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    HikariDataSource dataSource = context.getBean(HikariDataSource.class);
                    assertThat(dataSource.getConnectionTimeout()).isEqualTo(3_000L);
                    assertThat(dataSource.getLeakDetectionThreshold()).isEqualTo(60_000L);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties
    static class HikariBindingConfiguration {

        @Bean
        @ConfigurationProperties("spring.datasource.hikari")
        HikariDataSource hikariDataSource() {
            return new HikariDataSource();
        }
    }
}
