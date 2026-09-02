package com.aifriend.invitation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 受限邀请会话主配置绑定回归测试。
 *
 * <p>配置 record 同时保留规范构造器和隔离测试便捷构造器时，必须明确选择规范构造器，
 * 防止部署启动阶段退回无参 JavaBean 绑定并失败。
 *
 * @author Codex
 * @since 1.0.0
 */
class InvitationSessionPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfiguration.class)
            .withPropertyValues(
                    "ai-friend.invitation-session.cookie-name=__Host-ai_friend_invitation",
                    "ai-friend.invitation-session.ttl=30m",
                    "ai-friend.invitation-session.consent-policy-version=invitation-consent-v1",
                    "ai-friend.invitation-session.continuation-url=https://invite.example.com/invite/continue",
                    "ai-friend.invitation-session.unavailable-url=https://invite.example.com/invite/unavailable");

    @Test
    void shouldBindCanonicalConstructorWhenConvenienceConstructorAlsoExists() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            InvitationSessionProperties properties = context.getBean(InvitationSessionProperties.class);
            assertThat(properties.cookieName()).isEqualTo("__Host-ai_friend_invitation");
            assertThat(properties.ttl()).isEqualTo(Duration.ofMinutes(30));
            assertThat(properties.consentPolicyVersion()).isEqualTo("invitation-consent-v1");
            assertThat(properties.continuationUrl())
                    .isEqualTo(URI.create("https://invite.example.com/invite/continue"));
            assertThat(properties.unavailableUrl())
                    .isEqualTo(URI.create("https://invite.example.com/invite/unavailable"));
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(InvitationSessionProperties.class)
    static class BindingConfiguration {
    }
}
