package com.aifriend.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.contact.application.AcousticTemplatePort;
import com.aifriend.knowledge.application.ContactGraphQueryService;
import com.aifriend.knowledge.application.ContactGraphSourcePort;
import com.aifriend.knowledge.application.KnowledgeGraphPort;
import com.aifriend.shared.security.SensitiveDataProtector;

class KnowledgeGraphConfigurationTest {
    private final DataSource source = mock(DataSource.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ConsentGrantQueryPort consents = mock(ConsentGrantQueryPort.class);
    private final AcousticTemplatePort compatibility = mock(AcousticTemplatePort.class);
    private final SensitiveDataProtector protector = mock(SensitiveDataProtector.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KnowledgeGraphConfiguration.class)
            .withBean(DataSource.class, () -> source)
            .withBean(PlatformTransactionManager.class, () -> transactions)
            .withBean(ConsentGrantQueryPort.class, () -> consents)
            .withBean(com.aifriend.assistant.application.KnowledgeAccessPolicy.class,
                    () -> new com.aifriend.assistant.application.KnowledgeAccessPolicy(consents))
            .withBean(AcousticTemplatePort.class, () -> compatibility)
            .withBean(SensitiveDataProtector.class, () -> protector);

    @Test void bothSwitchesMustBeExplicitlyEnabledAndOldTaskFlagsDoNotEnableGraph() {
        for (String[] flags : new String[][]{{}, {"ai-friend.knowledge.enabled=true"},
                {"ai-friend.knowledge.graph-enabled=true"}, {"ai-friend.task.semantic-model.enabled=true"}}) {
            new ApplicationContextRunner().withUserConfiguration(KnowledgeGraphConfiguration.class)
                    .withPropertyValues(flags).run(context -> assertThat(context).hasNotFailed()
                            .doesNotHaveBean(ContactGraphQueryService.class).doesNotHaveBean(KnowledgeGraphPort.class));
        }
    }

    @Test void graphWorksWithoutAnyEmbeddingOrGenerationBeansAndDoesNotConnectDuringConstruction() {
        runner.withPropertyValues("ai-friend.knowledge.enabled=true", "ai-friend.knowledge.graph-enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ContactGraphQueryService.class)
                            .hasSingleBean(ContactGraphSourcePort.class).hasSingleBean(KnowledgeGraphPort.class);
                    assertThat(context.getBean(ContactGraphSourcePort.class))
                            .isInstanceOf(com.aifriend.knowledge.application.ContactGraphEvidencePort.class);
                    var access=context.getBean(com.aifriend.assistant.application.KnowledgeAccessPolicy.class);
                    for(var component:new Object[]{context.getBean(ContactGraphSourcePort.class),context.getBean(KnowledgeGraphPort.class),context.getBean(ContactGraphQueryService.class)}) {
                        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(component,"access")).isSameAs(access);
                    }
                    verifyNoInteractions(source, transactions, consents, protector, compatibility);
                });
    }

    @Test void mainYamlBindsIndependentGraphEnvironmentFlag() {
        runner.withInitializer(context -> {
            try {
                new YamlPropertySourceLoader().load("main", new FileSystemResource("src/main/resources/application.yml"))
                        .forEach(value -> context.getEnvironment().getPropertySources().addLast(value));
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture-env",
                        Map.of("AI_FRIEND_KNOWLEDGE_ENABLED", "true", "AI_FRIEND_KNOWLEDGE_GRAPH_ENABLED", "true")));
            } catch (Exception exception) { throw new IllegalStateException(exception); }
        }).run(context -> assertThat(context).hasNotFailed().hasSingleBean(ContactGraphQueryService.class));
    }
}
