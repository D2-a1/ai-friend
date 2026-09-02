package com.aifriend.invitation.infrastructure;

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

import com.aifriend.invitation.application.WechatInvitationAuthorizationPort;
import com.aifriend.invitation.application.WechatInvitationIdentityPort;
import com.aifriend.invitation.application.WechatInvitationOAuthProperties;

/**
 * 生产微信邀请网页 OAuth 条件装配测试。
 *
 * @author Codex
 * @since 1.0.0
 */
class WechatInvitationOAuthConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "ai-friend.wechat.invitation-oauth.authorization-endpoint="
                            + "https://open.weixin.qq.com/connect/oauth2/authorize",
                    "ai-friend.wechat.invitation-oauth.token-endpoint="
                            + "https://api.weixin.qq.com/sns/oauth2/access_token",
                    "ai-friend.wechat.invitation-oauth.callback-url="
                            + "https://invite.example.com/oauth/wechat/invitation-callback",
                    "ai-friend.wechat.invitation-oauth.connect-timeout=2s",
                    "ai-friend.wechat.invitation-oauth.read-timeout=3s");

    @Test
    void shouldProvideFailClosedAdaptersWhenProductionSwitchIsDisabled() {
        contextRunner.withPropertyValues("ai-friend.wechat.invitation-oauth.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(WechatInvitationAuthorizationPort.class)
                            .hasSingleBean(WechatInvitationIdentityPort.class);
                    assertThat(context.getBean(WechatInvitationAuthorizationPort.class))
                            .isInstanceOf(UnavailableWechatInvitationAuthorizationAdapter.class);
                    assertThat(context.getBean(WechatInvitationIdentityPort.class))
                            .isInstanceOf(UnavailableWechatInvitationIdentityAdapter.class);
                });
    }

    @Test
    void shouldPreferRealAdaptersOnlyWhenSwitchAndCredentialsAreValid() {
        contextRunner.withPropertyValues(
                        "ai-friend.wechat.invitation-oauth.enabled=true",
                        "ai-friend.wechat.invitation-oauth.app-id=wx-invitation-app-id",
                        "ai-friend.wechat.invitation-oauth.app-secret="
                                + "invitation-app-secret-0123456789",
                        "ai-friend.wechat.invitation-oauth.callback-url="
                                + "https://api.ai-friend.asia/api/v1/oauth/wechat/invitation-callback")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(WechatInvitationAuthorizationPort.class))
                            .hasSize(2);
                    assertThat(context.getBeansOfType(WechatInvitationIdentityPort.class))
                            .hasSize(2);
                    assertThat(context.getBean(WechatInvitationAuthorizationPort.class))
                            .isInstanceOf(WechatInvitationAuthorizationAdapter.class);
                    assertThat(context.getBean(WechatInvitationIdentityPort.class))
                            .isInstanceOf(WechatInvitationIdentityAdapter.class);
                });
    }

    @Test
    void shouldRejectStartupWhenEnabledWithoutCredentialsOrRealCallback() {
        contextRunner.withPropertyValues("ai-friend.wechat.invitation-oauth.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WechatInvitationOAuthProperties.class)
    @Import({
            WechatInvitationOAuthConfiguration.class,
            UnavailableWechatInvitationAuthorizationAdapter.class,
            UnavailableWechatInvitationIdentityAdapter.class
    })
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
