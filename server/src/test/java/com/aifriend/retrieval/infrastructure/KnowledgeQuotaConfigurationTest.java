package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

import com.aifriend.retrieval.application.KnowledgeQuotaPort;

class KnowledgeQuotaConfigurationTest {
    private final DataSource source = mock(DataSource.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KnowledgeQuotaConfiguration.class)
            .withBean(DataSource.class, () -> source)
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));

    @Test void defaultOffDoesNotCreateStorageOrConnect() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(KnowledgeQuotaPort.class);
            verifyNoInteractions(source);
        });
    }

    @Test void rootOnStillDefaultsQuotaClosedAndDoesNotConnectAtStartup() {
        runner.withPropertyValues("ai-friend.knowledge.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(KnowledgeQuotaPort.class);
            assertThat(context.getBean(KnowledgeQuotaProperties.class).enabled()).isFalse();
            verifyNoInteractions(source);
        });
    }

    @Test void enabledRequiresExplicitOwnerLimitAndRejectsMismatchedCaps() {
        runner.withPropertyValues("ai-friend.knowledge.quota.enabled=true").run(context -> assertThat(context).hasFailed());
        assertThatThrownBy(() -> new KnowledgeQuotaProperties(true, 61, 1, 1, 1, 1, 1, 1))
                .hasMessage("INVALID_KNOWLEDGE_QUOTA_CONFIGURATION");
        assertThatThrownBy(() -> new KnowledgeQuotaProperties(true, 6, 10, 5, 1, 1, 1, 1))
                .hasMessage("INVALID_KNOWLEDGE_QUOTA_CONFIGURATION");
        assertThatThrownBy(() -> new KnowledgeQuotaProperties(true, 6, 10, 10, 11, 11, 1, 1))
                .hasMessage("INVALID_KNOWLEDGE_QUOTA_CONFIGURATION");
    }

    @Test void zeroFeeBudgetIsValidForLocalOnlyQuestionMode() {
        runner.withPropertyValues("ai-friend.knowledge.quota.enabled=true",
                "ai-friend.knowledge.quota.owner-requests-per-minute=6").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(KnowledgeQuotaProperties.class).globalDailyCalls()).isZero();
                });
    }
}
