package com.aifriend.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import com.aifriend.identity.application.WechatIdentityPort;
import com.aifriend.identity.application.WechatIdentityProperties;

/**
 * 生产微信身份适配器条件装配测试。
 *
 * @author Codex
 * @since 1.0.0
 */
class WechatIdentityConfigurationTest {

    private static final String TOKEN_ENDPOINT_PROPERTY =
            "ai-friend.wechat.identity.token-endpoint="
                    + "https://api.weixin.qq.com/sns/oauth2/access_token";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    TOKEN_ENDPOINT_PROPERTY,
                    "ai-friend.wechat.identity.connect-timeout=2s",
                    "ai-friend.wechat.identity.read-timeout=3s");

    @Test
    void shouldProvideFailClosedAdapterWhenProductionSwitchIsDisabled() {
        contextRunner.withPropertyValues("ai-friend.wechat.identity.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(WechatIdentityPort.class);
                    assertThat(context.getBean(WechatIdentityPort.class))
                            .isInstanceOf(UnavailableWechatIdentityAdapter.class);
                });
    }

    @Test
    void shouldPreferRealAdapterOnlyWhenSwitchAndCredentialsAreValid() {
        contextRunner.withPropertyValues(
                        "ai-friend.wechat.identity.enabled=true",
                        "ai-friend.wechat.identity.app-id=wx1234567890abcdef",
                        "ai-friend.wechat.identity.app-secret=0123456789abcdef0123456789abcdef")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(WechatIdentityPort.class)).hasSize(2);
                    assertThat(context.getBean(WechatIdentityPort.class))
                            .isInstanceOf(WechatIdentityAdapter.class);
                });
    }

    @Test
    void shouldRejectStartupWhenSwitchIsEnabledWithoutCredentials() {
        contextRunner.withPropertyValues("ai-friend.wechat.identity.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WechatIdentityProperties.class)
    @Import({WechatIdentityConfiguration.class, UnavailableWechatIdentityAdapter.class})
    static class TestConfiguration {

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        CircuitBreakerRegistry circuitBreakerRegistry() {
            return CircuitBreakerRegistry.ofDefaults();
        }

        @Bean
        BulkheadRegistry bulkheadRegistry() {
            return BulkheadRegistry.ofDefaults();
        }
    }
}
