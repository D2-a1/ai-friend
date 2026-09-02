package com.aifriend.task.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** 生产 server.env 微信执行批准组合绑定回归测试。 */
class WechatExecutionPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfiguration.class)
            .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                    new SystemEnvironmentPropertySource(
                            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            Map.of(
                                    "AI_FRIEND_WECHAT_EXECUTION_ENABLED", "true",
                                    "AI_FRIEND_WECHAT_EXECUTION_APPROVED_CLIENT_COMBINATIONS_0_APP_VERSION",
                                    "0.0.1",
                                    "AI_FRIEND_WECHAT_EXECUTION_APPROVED_CLIENT_COMBINATIONS_0_WECHAT_VERSION",
                                    "8.0.76",
                                    "AI_FRIEND_WECHAT_EXECUTION_APPROVED_CLIENT_COMBINATIONS_0_RULE_VERSION",
                                    "wechat-semantic-call-v1"))));

    @Test
    void shouldBindIndexedApprovedCombinationFromServerEnvironment() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            WechatExecutionProperties properties = context.getBean(
                    WechatExecutionProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.approvedClientCombinations()).containsExactly(
                    new WechatExecutionProperties.ApprovedClientCombination(
                            "0.0.1",
                            "8.0.76",
                            "wechat-semantic-call-v1"));
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WechatExecutionProperties.class)
    static class BindingConfiguration {
    }
}
