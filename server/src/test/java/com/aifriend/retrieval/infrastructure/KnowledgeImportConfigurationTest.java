package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import com.aifriend.retrieval.application.EmbeddingPort;
import com.aifriend.retrieval.application.KnowledgeGenerationPort;
import com.aifriend.retrieval.application.KnowledgeImportWorker;
import com.aifriend.retrieval.application.KnowledgeImportRegistrationPort;
import com.aifriend.retrieval.application.KnowledgeImportRegistrationPort.BuildSpecification;

class KnowledgeImportConfigurationTest {
    private final DataSource source = mock(DataSource.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KnowledgeImportConfiguration.class, KnowledgeQuotaConfiguration.class, KnowledgeEmbeddingConfiguration.class)
            .withBean(DataSource.class, () -> source)
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(KnowledgeImportScheduler.class, () -> mock(KnowledgeImportScheduler.class));
    private final String[] enabled = {"ai-friend.knowledge.enabled=true", "ai-friend.knowledge.import.enabled=true"};
    private final String[] embedding = {"ai-friend.knowledge.embedding.enabled=true",
            "ai-friend.knowledge.embedding.endpoint=https://embedding.unit-domain.org/v1/embeddings",
            "ai-friend.knowledge.embedding.allowed-hosts=embedding.unit-domain.org",
            "ai-friend.knowledge.embedding.model=fixture-model", "ai-friend.knowledge.embedding.api-key=not-a-real-key",
            "ai-friend.knowledge.embedding.dimension=2", "ai-friend.knowledge.embedding.profile-id=p1",
            "ai-friend.knowledge.embedding.batch-size=32", "ai-friend.knowledge.embedding.connect-timeout=1s",
            "ai-friend.knowledge.embedding.read-timeout=8s"};

    @Test void bothFlagsAreRequiredBeforeAnyImportStorageIsWired() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(KnowledgeImportWorker.class));
        runner.withPropertyValues("ai-friend.knowledge.import.enabled=true").run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(KnowledgeImportWorker.class));
        runner.withPropertyValues("ai-friend.knowledge.enabled=true").run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(KnowledgeImportWorker.class));
        verifyNoInteractions(source);
    }

    @Test void lexicalModeWiresRealRegistrationBuildAndPublicationWithoutConnectingDatabase() {
        runner.withPropertyValues(enabled).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(KnowledgeImportWorker.class)
                    .hasSingleBean(KnowledgeImportRegistrationPort.class).hasSingleBean(KnowledgeGenerationPort.class)
                    .hasSingleBean(com.aifriend.retrieval.application.KnowledgeDeletionRebuildPort.class)
                    .hasSingleBean(com.aifriend.retrieval.application.KnowledgeCleanupRetryPort.class)
                    .doesNotHaveBean(EmbeddingPort.class);
            assertThat(context.getBean(com.aifriend.retrieval.application.KnowledgeHistoryMaintenancePort.class))
                    .isInstanceOf(com.aifriend.retrieval.application.RetryingKnowledgeHistoryMaintenance.class);
            assertThat(context.getBean(BuildSpecification.class).embeddingProfile()).isEmpty();
            verifyNoInteractions(source);
        });
    }

    @Test void importAndOnlineEmbeddingsHaveIndependentTransportAndBulkheadInstances() {
        runner.withPropertyValues(enabled).withPropertyValues(embedding).run(context -> {
            assertThat(context).hasNotFailed();
            var online = context.getBean("knowledgeEmbeddingPort", EmbeddingsHttpAdapter.class);
            var importing = context.getBean("knowledgeImportEmbeddingPort", EmbeddingsHttpAdapter.class);
            assertThat(importing).isNotSameAs(online);
            assertThat(ReflectionTestUtils.getField(importing, "transport")).isNotSameAs(ReflectionTestUtils.getField(online, "transport"));
            var importBulkhead = (io.github.resilience4j.bulkhead.Bulkhead) ReflectionTestUtils.getField(importing, "bulkhead");
            var onlineBulkhead = (io.github.resilience4j.bulkhead.Bulkhead) ReflectionTestUtils.getField(online, "bulkhead");
            assertThat(importBulkhead.getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(1);
            assertThat(onlineBulkhead.getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(4);
            verifyNoInteractions(source);
        });
    }

    @Test void mismatchedBatchOrOutOfRangeMemoryFailsAtStartup() {
        runner.withPropertyValues(enabled).withPropertyValues(embedding)
                .withPropertyValues("ai-friend.knowledge.import.batch-size=64")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(enabled).withPropertyValues("ai-friend.knowledge.import.maximum-snapshot-bytes=134217729")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void publishedEnvironmentVariableNamesActuallyBindThroughApplicationYaml() {
        runner.withInitializer(context -> {
            try {
                // test/resources/application.yml shadows the main resource on the test classpath.
                var yaml = new YamlPropertySourceLoader().load("application-defaults",
                        new FileSystemResource("src/main/resources/application.yml"));
                yaml.forEach(p -> context.getEnvironment().getPropertySources().addLast(p));
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture-environment", Map.of(
                        "AI_FRIEND_KNOWLEDGE_ENABLED", "true", "AI_FRIEND_KNOWLEDGE_IMPORT_ENABLED", "true",
                        "AI_FRIEND_KNOWLEDGE_IMPORT_BATCH_SIZE", "16", "AI_FRIEND_KNOWLEDGE_QUOTA_ENABLED", "true",
                        "AI_FRIEND_KNOWLEDGE_QUOTA_OWNER_REQUESTS_PER_MINUTE", "7",
                        "AI_FRIEND_KNOWLEDGE_CHUNK_TARGET_CHARS", "300")));
            } catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
        }).run(context -> {
            assertThat(context.getEnvironment().getProperty("ai-friend.knowledge.enabled")).isEqualTo("true");
            assertThat(context.getEnvironment().getProperty("ai-friend.knowledge.import.enabled")).isEqualTo("true");
            assertThat(context).hasNotFailed().hasSingleBean(KnowledgeImportWorker.class);
            assertThat(context.getBean(KnowledgeImportProperties.class).batchSize()).isEqualTo(16);
            assertThat(context.getBean(KnowledgeQuotaProperties.class).ownerRequestsPerMinute()).isEqualTo(7);
            assertThat(context.getBean(BuildSpecification.class).chunkerVersion()).contains("t300");
            verifyNoInteractions(source);
        });
    }
}
