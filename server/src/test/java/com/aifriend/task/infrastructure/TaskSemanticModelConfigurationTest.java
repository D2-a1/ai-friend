package com.aifriend.task.infrastructure;

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

import com.aifriend.task.application.TaskConversationUnderstandingPort;
import com.aifriend.task.application.TaskSemanticModelProperties;

class TaskSemanticModelConfigurationTest {

    private static final String[] MODEL_PROPERTIES = {
        "ai-friend.task-semantic-model.endpoint="
                + "https://chat.vendor-one.net/v1/chat/completions",
        "ai-friend.task-semantic-model.allowed-hosts=chat.vendor-one.net",
        "ai-friend.task-semantic-model.model=semantic-model-v1",
        "ai-friend.task-semantic-model.api-key=test-semantic-model-key",
        "ai-friend.task-semantic-model.token-limit-field=MAX_COMPLETION_TOKENS",
        "ai-friend.task-semantic-model.max-output-tokens=256",
        "ai-friend.task-semantic-model.temperature=0.2",
        "ai-friend.task-semantic-model.thinking-mode=DISABLED",
        "ai-friend.task-semantic-model.connect-timeout=2s",
        "ai-friend.task-semantic-model.read-timeout=6s"
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(MODEL_PROPERTIES);

    @Test
    void disabledSwitchMustKeepOnlyConservativePort() {
        contextRunner.withPropertyValues(
                        "ai-friend.task-semantic-model.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(
                            TaskConversationUnderstandingPort.class)).hasSize(1);
                    assertThat(context.getBean(TaskConversationUnderstandingPort.class))
                            .isInstanceOf(LocalContextualTaskUnderstandingAdapter.class);
                });
    }

    @Test
    void enabledSwitchMustPreferRealModelWithoutRemovingSafetyFallback() {
        contextRunner.withPropertyValues(
                        "ai-friend.task-semantic-model.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(
                            TaskConversationUnderstandingPort.class)).hasSize(2);
                    assertThat(context.getBean(TaskConversationUnderstandingPort.class))
                            .isInstanceOf(ChatCompletionsTaskUnderstandingAdapter.class);
                    TaskSemanticModelProperties properties =
                            context.getBean(TaskSemanticModelProperties.class);
                    assertThat(properties.allowedHosts())
                            .containsExactly("chat.vendor-one.net");
                    assertThat(properties.maxOutputTokens()).isEqualTo(256);
                });
    }

    @Test
    void enabledSwitchMustRejectEndpointOutsideExternalAllowedHosts() {
        contextRunner.withPropertyValues(
                        "ai-friend.task-semantic-model.enabled=true",
                        "ai-friend.task-semantic-model.allowed-hosts=chat.vendor-two.net")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void testProfileMustNeverCreateExternalModelEvenWhenEnabled() {
        new ApplicationContextRunner()
                .withInitializer(context ->
                        context.getEnvironment().setActiveProfiles("test"))
                .withUserConfiguration(TestConfiguration.class)
                .withPropertyValues(MODEL_PROPERTIES)
                .withPropertyValues("ai-friend.task-semantic-model.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(
                            TaskConversationUnderstandingPort.class)).hasSize(1);
                    assertThat(context.getBean(TaskConversationUnderstandingPort.class))
                            .isInstanceOf(LocalContextualTaskUnderstandingAdapter.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TaskSemanticModelProperties.class)
    @Import(TaskSemanticModelConfiguration.class)
    @org.springframework.context.annotation.ComponentScan(
            basePackageClasses = LocalContextualTaskUnderstandingAdapter.class,
            useDefaultFilters = false,
            includeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                    type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                    classes = LocalContextualTaskUnderstandingAdapter.class))
    static class TestConfiguration {

        @Bean
        com.aifriend.task.application.TaskUtteranceInterpretationService interpretationService() {
            return new com.aifriend.task.application.TaskUtteranceInterpretationService(
                    new LocalKeywordTaskIntentAdapter(),
                    new com.aifriend.task.application.TaskAudioAlignmentService());
        }

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
