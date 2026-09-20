package com.aifriend.assistant.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import com.aifriend.assistant.application.*;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.consent.infrastructure.CompositeConsentRevocationCleanupAdapter;
import com.aifriend.contact.application.AcousticTemplatePort;
import com.aifriend.knowledge.application.*;
import com.aifriend.knowledge.domain.GraphQueryType;
import com.aifriend.knowledge.infrastructure.KnowledgeGraphConfiguration;
import com.aifriend.retrieval.application.*;
import com.aifriend.shared.security.SensitiveDataProtector;

/** Spring真实装配，基础设施替身；构造/关闭不连接数据源、不调用外部模型。 */
class AssistantSessionConfigurationTest {
    private final DataSource source=mock(DataSource.class);
    private final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    private final ConsentGrantQueryPort consents=mock(ConsentGrantQueryPort.class);
    private final KnowledgeQuotaPort quota=mock(KnowledgeQuotaPort.class);
    private final SensitiveDataProtector protector=mock(SensitiveDataProtector.class);
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
            .withUserConfiguration(AssistantSessionConfiguration.class,KnowledgeAnswerConfiguration.class)
            .withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->transactions)
            .withBean(ConsentGrantQueryPort.class,()->consents).withBean(KnowledgeQuotaPort.class,()->quota)
            .withBean(SensitiveDataProtector.class,()->protector).withBean(Clock.class,Clock::systemUTC);

    @Test void defaultOffKeepsCleanupButNoOnlineServicesOrThreadPool() {
        runner.run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(AssistantSessionLifecyclePort.class)
                    .hasSingleBean(AssistantConsentRevocationHandler.class).doesNotHaveBean(AssistantSessionService.class)
                    .doesNotHaveBean(AssistantExecutionPort.class).doesNotHaveBean(AssistantSessionRepository.class);
            verifyNoInteractions(source,transactions,consents,quota,protector);
        });
    }
    @Test void extractiveWiresRepositoriesReaderCipherAndExplicitBoundedExecution() {
        runner.withPropertyValues("ai-friend.knowledge.enabled=true","ai-friend.knowledge.request.workers=1",
                "ai-friend.knowledge.request.queue-capacity=0").run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(AssistantSessionService.class).hasSingleBean(AssistantSessionRepository.class)
                    .hasSingleBean(AssistantTurnRepository.class).hasSingleBean(AssistantResultReader.class)
                    .hasSingleBean(AssistantResultProtectionPort.class).hasSingleBean(AssistantExecutionPort.class);
            assertThat(context.getBean(AssistantSessionRepository.class)).isInstanceOf(JdbcAssistantSessionRepository.class);
            assertThat(context.getBean(AssistantTurnRepository.class)).isInstanceOf(JdbcAssistantTurnRepository.class);
            assertThat(ReflectionTestUtils.getField(context.getBean(AssistantSessionService.class),"execution"))
                    .isSameAs(context.getBean(BoundedAssistantExecutor.class));
            assertThat(ReflectionTestUtils.getField(context.getBean(AssistantSessionService.class),"externalProcessing")).isEqualTo(false);
            assertThat(context.getBean(AssistantExecutionProperties.class)).isEqualTo(new AssistantExecutionProperties(1,0));
            verifyNoInteractions(source,transactions,consents,quota,protector);
        });
    }
    @Test void disabledGraphIsExplicitRejectionEvenIfGraphBeansExist() {
        var graph=mock(GraphQueryPort.class); var graphSource=mock(ContactGraphSourcePort.class);
        runner.withPropertyValues("ai-friend.knowledge.enabled=true","ai-friend.knowledge.graph-enabled=false")
                .withBean(GraphQueryPort.class,()->graph).withBean(ContactGraphSourcePort.class,()->graphSource).run(context->{
            assertThat(context).hasNotFailed();
            var query=(GraphQueryPort)ReflectionTestUtils.getField(context.getBean(AssistantSessionService.class),"graph");
            assertThatThrownBy(()->query.query(new GraphQueryPort.Query(UUID.randomUUID(),GraphQueryType.LIST_CONTACTS,Optional.empty(),Optional.empty())))
                    .hasMessage("CONFIG_INVALID");
            var selected=(ContactGraphSourcePort)ReflectionTestUtils.getField(context.getBean(AssistantResultRevalidator.class),"graph");
            assertThatThrownBy(()->selected.snapshot(UUID.randomUUID())).hasMessage("CONFIG_INVALID");
            verifyNoInteractions(graph,graphSource,source,transactions);
        });
    }
    @Test void graphEnabledRequiresBothRealPortsAndWiresWithoutModel() {
        runner.withPropertyValues("ai-friend.knowledge.enabled=true","ai-friend.knowledge.graph-enabled=true")
                .run(context->assertThat(context).hasFailed());
        var compatibility=mock(AcousticTemplatePort.class);
        runner.withUserConfiguration(KnowledgeGraphConfiguration.class).withBean(AcousticTemplatePort.class,()->compatibility)
                .withPropertyValues("ai-friend.knowledge.enabled=true","ai-friend.knowledge.graph-enabled=true").run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(ContactGraphQueryService.class).doesNotHaveBean(KnowledgeAnswerGenerationPort.class);
            assertThat(ReflectionTestUtils.getField(context.getBean(AssistantSessionService.class),"graph"))
                    .isSameAs(context.getBean(ContactGraphQueryService.class));
            assertThat(ReflectionTestUtils.getField(context.getBean(AssistantResultRevalidator.class),"graph"))
                    .isSameAs(context.getBean(ContactGraphSourcePort.class));
            verifyNoInteractions(compatibility,source,transactions,consents,quota,protector);
        });
    }
    @Test void onlyOnlineEmbeddingEnablesExternalQuestionConsentMode() {
        var embedding=mock(EmbeddingPort.class);
        for(boolean online:new boolean[]{false,true}) {
            runner.withPropertyValues("ai-friend.knowledge.enabled=true")
                    .withBean(online?"knowledgeEmbeddingPort":"knowledgeImportEmbeddingPort",EmbeddingPort.class,()->embedding).run(context->{
                assertThat(context).hasNotFailed();
                assertThat(ReflectionTestUtils.getField(context.getBean(AssistantSessionService.class),"externalProcessing")).isEqualTo(online);
                verifyNoInteractions(embedding,source,transactions,consents,quota,protector);
            });
        }
    }
    @Test void generatedModeUsesDedicatedProfileButDoesNotCallGenerationOnStartup() {
        var generation=mock(KnowledgeAnswerGenerationPort.class); when(generation.profileId()).thenReturn("fixture-v1");
        runner.withPropertyValues("ai-friend.knowledge.enabled=true","ai-friend.knowledge.answer.mode=GENERATED")
                .withBean(KnowledgeAnswerGenerationPort.class,()->generation).run(context->{
            assertThat(context).hasNotFailed(); verifyNoInteractions(generation);
            assertThat(ReflectionTestUtils.getField(context.getBean(AssistantSessionService.class),"externalProcessing")).isEqualTo(true);
            var profile=(Supplier<?>)context.getBean("assistantGenerationProfile");
            assertThat(profile.get()).isEqualTo(Optional.of("fixture-v1")); verify(generation).profileId(); verifyNoMoreInteractions(generation);
        });
    }
    @Test void invalidWorkerConfigurationFailsInsteadOfUnboundedFallback() {
        runner.withPropertyValues("ai-friend.knowledge.enabled=true","ai-friend.knowledge.request.workers=17")
                .run(context->assertThat(context).hasFailed());
        runner.withPropertyValues("ai-friend.knowledge.enabled=true","ai-friend.knowledge.request.queue-capacity=-1")
                .run(context->assertThat(context).hasFailed());
    }
    @Test void contextCloseClosesExecutorAndDoesNotLeaveReusableWorkers() {
        var reference=new AtomicReference<BoundedAssistantExecutor>();
        runner.withPropertyValues("ai-friend.knowledge.enabled=true").run(context->{
            assertThat(context).hasNotFailed(); reference.set(context.getBean(BoundedAssistantExecutor.class));
            reference.get().execute(Instant.now().plusSeconds(2),control->control.check());
        });
        assertThatThrownBy(()->reference.get().execute(Instant.now().plusSeconds(2),control->{})).hasMessage("RESOURCE_LIMIT");
    }
    @Test void bothIndependentRevocationsReachCleanupWithAllFeaturesOff() {
        var lifecycle=mock(AssistantSessionLifecyclePort.class); UUID owner=UUID.randomUUID();
        runner.withUserConfiguration(CompositeConsentRevocationCleanupAdapter.class)
                .withBean(AssistantSessionLifecyclePort.class,()->lifecycle).run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(AssistantSessionLifecyclePort.class);
            var composite=context.getBean(CompositeConsentRevocationCleanupAdapter.class);
            composite.cleanup(owner,ConsentType.KNOWLEDGE_MODEL,Instant.EPOCH);
            composite.cleanup(owner,ConsentType.CONTACT_GRAPH,Instant.EPOCH);
            verify(lifecycle).revoke(owner,Purpose.PUBLIC_KNOWLEDGE); verify(lifecycle).revoke(owner,Purpose.CONTACT_GRAPH);
            verifyNoMoreInteractions(lifecycle); verifyNoInteractions(source,transactions,protector);
        });
    }
    @Test void actualYamlMapsExternalWorkerEnvironmentNames() {
        runner.withInitializer(context->{
            try {
                new org.springframework.boot.env.YamlPropertySourceLoader().load("main",
                        new org.springframework.core.io.FileSystemResource("src/main/resources/application.yml"))
                        .forEach(p->context.getEnvironment().getPropertySources().addLast(p));
                context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("fixture-env",
                        Map.of("AI_FRIEND_KNOWLEDGE_ENABLED","true","AI_FRIEND_KNOWLEDGE_REQUEST_WORKERS","3",
                                "AI_FRIEND_KNOWLEDGE_REQUEST_QUEUE_CAPACITY","7")));
            } catch(Exception failure) { throw new IllegalStateException(failure); }
        }).run(context->{
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AssistantExecutionProperties.class)).isEqualTo(new AssistantExecutionProperties(3,7));
            verifyNoInteractions(source,transactions,quota,consents,protector);
        });
    }
}
