package com.aifriend.assistant.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import com.aifriend.assistant.application.KnowledgeAnswerService;
import com.aifriend.assistant.domain.AssistantAnswer.Mode;
import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.retrieval.application.*;
import com.aifriend.retrieval.infrastructure.JdbcKnowledgeAdapter;

class KnowledgeAnswerConfigurationTest {
    private final DataSource source = mock(DataSource.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final KnowledgeQuotaPort quota = mock(KnowledgeQuotaPort.class);
    private final ConsentGrantQueryPort consents = mock(ConsentGrantQueryPort.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KnowledgeAnswerConfiguration.class)
            .withBean(DataSource.class, () -> source)
            .withBean(PlatformTransactionManager.class, () -> transactions)
            .withBean(KnowledgeQuotaPort.class, () -> quota)
            .withBean(ConsentGrantQueryPort.class, () -> consents)
            .withBean(Clock.class, Clock::systemUTC);

    @Test void defaultOffAndOldTaskFlagsDoNotCreateAnswerService() {
        new ApplicationContextRunner().withUserConfiguration(KnowledgeAnswerConfiguration.class)
                .withPropertyValues("ai-friend.task.semantic-model.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(KnowledgeAnswerService.class));
    }

    @Test void defaultExtractiveWiresSingleAuthoritativeReaderWithoutConnectingDatabase() {
        runner.withPropertyValues("ai-friend.knowledge.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(KnowledgeAnswerService.class);
            assertThat(context.getBean(KnowledgeRepositoryPort.class)).isSameAs(context.getBean(KnowledgeVectorRepositoryPort.class))
                    .isInstanceOf(JdbcKnowledgeAdapter.class);
            assertThat(context.getBean(KnowledgeAnswerProperties.class).mode()).isEqualTo(Mode.EXTRACTIVE);
            assertThat(ReflectionTestUtils.getField(context.getBean(KnowledgeAnswerService.class), "generation"))
                    .isEqualTo(Optional.empty());
            assertThat(context.getBean(ConsentGrantQueryPort.class)).isSameAs(consents);
            assertThat(ReflectionTestUtils.getField(context.getBean(com.aifriend.assistant.application.KnowledgeAccessPolicy.class), "consents"))
                    .isInstanceOf(com.aifriend.consent.infrastructure.JdbcKnowledgeConsentQueryAdapter.class);
            verifyNoInteractions(source, quota, consents, transactions);
        });
    }

    @Test void generatedModeRequiresExplicitDedicatedClient() {
        runner.withPropertyValues("ai-friend.knowledge.enabled=true", "ai-friend.knowledge.answer.mode=GENERATED")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("ai-friend.knowledge.enabled=true", "ai-friend.knowledge.answer.mode=GENERATED")
                .withBean(KnowledgeAnswerGenerationPort.class, () -> mock(KnowledgeAnswerGenerationPort.class))
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(KnowledgeAnswerService.class));
    }

    @Test void onlineEmbeddingIsQualifiedWhenImportModelAlsoExists() {
        var online = mock(EmbeddingPort.class);
        var importing = mock(EmbeddingPort.class);
        runner.withPropertyValues("ai-friend.knowledge.enabled=true")
                .withBean("knowledgeEmbeddingPort", EmbeddingPort.class, () -> online)
                .withBean("knowledgeImportEmbeddingPort", EmbeddingPort.class, () -> importing)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(ReflectionTestUtils.getField(context.getBean(KnowledgeAnswerService.class), "embedding"))
                            .isEqualTo(Optional.of(online));
                    verifyNoInteractions(online, importing);
                });
    }

    @Test void numericAndModeBoundaryViolationsFailAtStartup() {
        for (String value : new String[]{"mode=TEMPLATE", "generation-budget=4001ms", "generation-budget=0ms",
                "bm25-k1=0", "bm25-k1=NaN", "bm25-b=1.1", "rrf-k=1001", "maximum-snapshot-bytes=134217729"}) {
            runner.withPropertyValues("ai-friend.knowledge.enabled=true", "ai-friend.knowledge.answer." + value)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test void mainYamlBindsActualEnvironmentNamesNotAssumedCloudValues() {
        runner.withInitializer(context -> {
            try {
                new YamlPropertySourceLoader().load("main", new FileSystemResource("src/main/resources/application.yml"))
                        .forEach(p -> context.getEnvironment().getPropertySources().addLast(p));
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture-env",
                        Map.of("AI_FRIEND_KNOWLEDGE_ENABLED", "true", "AI_FRIEND_KNOWLEDGE_ANSWER_MODE", "EXTRACTIVE",
                                "AI_FRIEND_KNOWLEDGE_ANSWER_GENERATION_BUDGET", "3s", "AI_FRIEND_KNOWLEDGE_BM25_K1", "1.5",
                                "AI_FRIEND_KNOWLEDGE_BM25_B", "0.5", "AI_FRIEND_KNOWLEDGE_RRF_K", "50",
                                "AI_FRIEND_KNOWLEDGE_INDEX_MAX_SNAPSHOT_BYTES", "67108864")));
            } catch (Exception exception) { throw new IllegalStateException(exception); }
        }).run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(KnowledgeAnswerProperties.class);
            assertThat(properties.generationBudget()).isEqualTo(java.time.Duration.ofSeconds(3));
            assertThat(properties.bm25K1()).isEqualTo(1.5);
            assertThat(properties.bm25B()).isEqualTo(.5);
            assertThat(properties.rrfK()).isEqualTo(50);
            assertThat(properties.maximumSnapshotBytes()).isEqualTo(67108864);
        });
    }
}
