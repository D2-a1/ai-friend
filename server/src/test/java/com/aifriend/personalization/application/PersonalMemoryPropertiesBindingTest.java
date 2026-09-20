package com.aifriend.personalization.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PersonalMemoryPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfiguration.class)
            .withPropertyValues(
                    "ai-friend.personal-memory.enabled=false",
                    "ai-friend.personal-memory.policy-version=personal-memory-v1");

    @Test
    void shouldBindDefaultOffConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            PersonalMemoryProperties properties = context.getBean(
                    PersonalMemoryProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.policyVersion()).isEqualTo("personal-memory-v1");
        });
    }

    @Test
    void shouldRejectMalformedPolicyVersion() {
        contextRunner.withPropertyValues(
                        "ai-friend.personal-memory.policy-version=contains space")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PersonalMemoryProperties.class)
    static class BindingConfiguration {
    }
}
