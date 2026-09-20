package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.aifriend.retrieval.application.EmbeddingPort;

class KnowledgeEmbeddingConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(KnowledgeEmbeddingConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test void defaultOffCreatesNoExternalClientOrRequests() {
        context.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(EmbeddingPort.class);
            assertThat(ctx.getBean(KnowledgeEmbeddingProperties.class).enabled()).isFalse();
        });
    }

    @Test void totalSwitchAloneCannotEnableEmbedding() {
        context.withPropertyValues("ai-friend.knowledge.enabled=true").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(EmbeddingPort.class);
        });
    }

    @Test void incompleteEnabledGroupFailsStartupWithoutFallback() {
        context.withPropertyValues("ai-friend.knowledge.enabled=true", "ai-friend.knowledge.embedding.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
