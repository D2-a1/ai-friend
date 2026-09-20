package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import javax.sql.DataSource;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

class KnowledgeMaintenanceConfigurationTest {
    final com.aifriend.assistant.application.AssistantSessionLifecyclePort sessions=mock(com.aifriend.assistant.application.AssistantSessionLifecyclePort.class);
    final DataSource source=mock(DataSource.class);
    final ScheduledExecutorService executor=mock(ScheduledExecutorService.class);
    final ApplicationContextRunner runner=new ApplicationContextRunner()
            .withUserConfiguration(KnowledgeMaintenanceConfiguration.class)
            .withBean(DataSource.class,()->source)
            .withBean(com.aifriend.assistant.application.AssistantSessionLifecyclePort.class,()->sessions)
            .withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class))
            .withBean("preventRealScheduling",BeanPostProcessor.class,()->new BeanPostProcessor() {
                @Override public Object postProcessBeforeInitialization(Object bean,String name) {
                    if(bean instanceof KnowledgeMaintenanceScheduler) ReflectionTestUtils.setField(bean,"executor",executor);
                    return bean;
                }
            });
    @Test void defaultAndDisabledHaveNoSchedulerOrDataAccess() {
        runner.run(c->assertThat(c).hasNotFailed().doesNotHaveBean(KnowledgeMaintenanceScheduler.class));
        runner.withPropertyValues("ai-friend.knowledge.maintenance.enabled=false").run(c->
                assertThat(c).hasNotFailed().doesNotHaveBean(KnowledgeMaintenanceScheduler.class));
        verifyNoInteractions(source,executor);
    }
    @Test void explicitMaintenanceWorksWhenBusinessAndImportAreOffWithoutConnectingDatabase() {
        runner.withPropertyValues("ai-friend.knowledge.maintenance.enabled=true",
                "ai-friend.knowledge.enabled=false","ai-friend.knowledge.import.enabled=false")
                .run(c->assertThat(c).hasNotFailed().hasSingleBean(KnowledgeMaintenanceScheduler.class));
        verifyNoInteractions(source);
        verify(executor).shutdownNow();
    }
    @Test void badIntervalFailsAtStartupNotSilentlyFallback() {
        runner.withPropertyValues("ai-friend.knowledge.maintenance.enabled=true",
                "ai-friend.knowledge.maintenance.poll-interval=0s").run(c->assertThat(c).hasFailed());
        verifyNoInteractions(source,executor);
    }
    @Test void sessionExpiryIsWiredAsBoundedStageEvenWhenBusinessIsOff() {
        runner.withPropertyValues("ai-friend.knowledge.maintenance.enabled=true",
                "ai-friend.knowledge.enabled=false","ai-friend.knowledge.import.enabled=false").run(c->{
            assertThat(c).hasNotFailed();
            var stages=(java.util.List<?>)ReflectionTestUtils.getField(c.getBean(KnowledgeMaintenanceScheduler.class),"stages");
            assertThat(stages).hasSize(4);
            verifyNoInteractions(sessions,source);
            ((Runnable)stages.get(3)).run();
            verify(sessions).expireBatch(16);
            verifyNoMoreInteractions(sessions);verifyNoInteractions(source);
        });
    }
    @Test void importAndMaintenanceCanCoexistWithoutDuplicatePortsOrDatabaseAccess() {
        runner.withUserConfiguration(KnowledgeImportConfiguration.class,KnowledgeQuotaConfiguration.class,KnowledgeEmbeddingConfiguration.class)
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,com.fasterxml.jackson.databind.ObjectMapper::new)
                .withBean(KnowledgeImportScheduler.class,()->mock(KnowledgeImportScheduler.class))
                .withPropertyValues("ai-friend.knowledge.maintenance.enabled=true","ai-friend.knowledge.enabled=true",
                        "ai-friend.knowledge.import.enabled=true","ai-friend.knowledge.maintenance.poll-interval=3s")
                .run(c->{
                    assertThat(c).hasNotFailed().hasSingleBean(KnowledgeMaintenanceScheduler.class)
                            .hasSingleBean(com.aifriend.retrieval.application.KnowledgeHistoryMaintenancePort.class)
                            .hasSingleBean(com.aifriend.retrieval.application.KnowledgeCleanupRetryPort.class);
                    assertThat(c.getBean(KnowledgeMaintenanceProperties.class).pollInterval()).isEqualTo(java.time.Duration.ofSeconds(3));
                });
        verifyNoInteractions(source);
    }
}
