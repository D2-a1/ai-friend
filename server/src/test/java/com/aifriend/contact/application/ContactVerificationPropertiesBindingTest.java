package com.aifriend.contact.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** 生产 server.env 本机验证白名单绑定回归测试。 */
class ContactVerificationPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfiguration.class)
            .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                    new SystemEnvironmentPropertySource(
                            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            Map.of(
                                    "AI_FRIEND_CONTACT_LOCAL_VERIFICATION_EVIDENCE_MAX_AGE", "5m",
                                    "AI_FRIEND_CONTACT_LOCAL_VERIFICATION_FUTURE_CLOCK_SKEW", "30s",
                                    "AI_FRIEND_CONTACT_LOCAL_VERIFICATION_ALLOWED_RULE_PAIRS",
                                    "8.0.76:wechat-contact-profile-v1,8.0.77:wechat-contact-profile-v1"))));

    @Test
    void shouldBindCommaSeparatedAllowedRulePairsFromServerEnvironment() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ContactVerificationProperties properties = context.getBean(
                    ContactVerificationProperties.class);
            assertThat(properties.evidenceMaxAge()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.futureClockSkew()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.allowedRulePairs()).containsExactly(
                    "8.0.76:wechat-contact-profile-v1",
                    "8.0.77:wechat-contact-profile-v1");
            assertThat(properties.supports("8.0.99", "wechat-contact-profile-v1")).isTrue();
            assertThat(properties.supports("8.0.76", "wechat-contact-profile-v2")).isFalse();
        });
    }

    @Test
    void emptyAllowedRulePairsMustRemainRejected() {
        ContactVerificationProperties properties = new ContactVerificationProperties(
                Duration.ofMinutes(5), Duration.ofSeconds(30), List.of());

        assertThat(properties.supports("8.0.76", "wechat-contact-profile-v1")).isFalse();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ContactVerificationProperties.class)
    static class BindingConfiguration {
    }
}
