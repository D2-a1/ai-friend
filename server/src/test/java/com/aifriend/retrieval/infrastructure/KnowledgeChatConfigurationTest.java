package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.ReflectionTestUtils;

import com.aifriend.retrieval.application.KnowledgeAnswerGenerationPort;

class KnowledgeChatConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KnowledgeChatConfiguration.class).withBean(ObjectMapper.class, ObjectMapper::new);
    private final String[] configured = {"ai-friend.knowledge.enabled=true", "ai-friend.knowledge.chat.enabled=true",
            "ai-friend.knowledge.chat.endpoint=https://chat.vendor.net/v1/chat/completions",
            "ai-friend.knowledge.chat.allowed-hosts=chat.vendor.net", "ai-friend.knowledge.chat.model=fixture",
            "ai-friend.knowledge.chat.api-key=fake-api-key", "ai-friend.knowledge.chat.profile-id=chat-v1",
            "ai-friend.knowledge.chat.quality-report-id=fixture-report", "ai-friend.knowledge.chat.token-limit-field=MAX_TOKENS",
            "ai-friend.knowledge.chat.max-output-tokens=512", "ai-friend.knowledge.chat.thinking-mode=OMIT",
            "ai-friend.knowledge.chat.connect-timeout=1s", "ai-friend.knowledge.chat.read-timeout=4s"};

    @Test void defaultDisabledAndTaskModelFlagsCannotEnableKnowledgeClient() {
        runner.withPropertyValues("ai-friend.task.semantic-model.enabled=true").run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(KnowledgeAnswerGenerationPort.class));
        runner.withPropertyValues("ai-friend.knowledge.enabled=true").run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(KnowledgeAnswerGenerationPort.class));
    }

    @Test void enabledClientUsesDedicatedBoundedTransportWithoutSending() {
        runner.withPropertyValues(configured).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(KnowledgeAnswerGenerationPort.class);
            var bean = context.getBean(ChatCompletionsKnowledgeAnswerAdapter.class);
            var transport = ReflectionTestUtils.getField(bean, "transport");
            assertThat(ReflectionTestUtils.getField(transport, "maximumResponseBytes")).isEqualTo(65536);
            assertThat(context.getBean(KnowledgeChatProperties.class).toString()).doesNotContain("fake-api-key", "vendor.net", "fixture");
        });
    }

    @Test void malformedOrIncompleteEnabledConfigurationFailsClosed() {
        runner.withPropertyValues("ai-friend.knowledge.chat.enabled=true").run(context -> assertThat(context).hasFailed());
        for (String override : new String[] {"endpoint=http://chat.vendor.net/v1/chat/completions",
                "endpoint=https://chat.vendor.net/v1/chat/completions?x=y",
                "endpoint=https://127.0.0.1/v1/chat/completions", "allowed-hosts=*.vendor.net",
                "allowed-hosts=chat.local", "quality-report-id=", "profile-id=",
                "read-timeout=5s", "max-output-tokens=2049", "temperature=NaN", "thinking-mode=UNKNOWN"}) {
            runner.withPropertyValues(configured).withPropertyValues("ai-friend.knowledge.chat." + override)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test void officialEnvironmentNamesBindFromMainYamlNotShadowTestResource() {
        runner.withInitializer(context -> {
            try {
                new YamlPropertySourceLoader().load("main-yaml", new FileSystemResource("src/main/resources/application.yml"))
                        .forEach(p -> context.getEnvironment().getPropertySources().addLast(p));
                var env = new java.util.HashMap<String, Object>();
                env.put("AI_FRIEND_KNOWLEDGE_ENABLED", "true");
                env.put("AI_FRIEND_KNOWLEDGE_CHAT_ENABLED", "true");
                env.put("AI_FRIEND_KNOWLEDGE_CHAT_ENDPOINT", "https://chat.vendor.net/v1/chat/completions");
                env.put("AI_FRIEND_KNOWLEDGE_CHAT_ALLOWED_HOSTS", "chat.vendor.net");
                env.put("AI_FRIEND_KNOWLEDGE_CHAT_MODEL", "fixture");
                env.put("AI_FRIEND_KNOWLEDGE_CHAT_API_KEY", "fake-api-key");
                env.put("AI_FRIEND_KNOWLEDGE_CHAT_PROFILE_ID", "chat-v1");
                env.put("AI_FRIEND_KNOWLEDGE_CHAT_QUALITY_REPORT_ID", "fixture-report");
                env.put("AI_FRIEND_KNOWLEDGE_CHAT_THINKING_MODE", "OBJECT_DISABLED");
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture-env", env));
            } catch (Exception e) { throw new IllegalStateException(e); }
        }).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(KnowledgeAnswerGenerationPort.class);
            assertThat(context.getBean(KnowledgeChatProperties.class).thinkingMode()).isEqualTo(KnowledgeChatProperties.ThinkingMode.OBJECT_DISABLED);
            assertThat(context.getBean(KnowledgeChatProperties.class).temperature()).isNull();
        });
    }
}
