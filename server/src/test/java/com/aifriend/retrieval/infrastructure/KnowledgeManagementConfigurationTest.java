package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayInputStream;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import com.aifriend.assistant.api.KnowledgeAdminController;
import com.aifriend.retrieval.application.KnowledgeDocumentManagementPort;
import com.aifriend.retrieval.application.KnowledgeImportRegistrationPort;
import com.aifriend.retrieval.application.KnowledgeCleanupStatusPort;

class KnowledgeManagementConfigurationTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "false,false,false,false", "false,false,true,true", "false,true,false,false", "false,true,true,true",
        "true,false,false,false", "true,false,true,true", "true,true,false,true", "true,true,true,true"
    })
    void localScanFlagReflectsBothSchedulingPathsWithoutSideEffects(boolean enabled,boolean importing,boolean maintenance,boolean expected) {
        var documents=mock(KnowledgeDocumentManagementPort.class);
        var cleanup=mock(KnowledgeCleanupStatusPort.class);
        var now=java.time.Instant.parse("2026-09-12T12:00:00Z");
        when(cleanup.read()).thenReturn(new KnowledgeCleanupStatusPort.Snapshot(now,null,now,0,
                KnowledgeCleanupStatusPort.LeaseState.NONE,0,0,0,false,0,0));
        new ApplicationContextRunner().withUserConfiguration(KnowledgeAdminController.class)
                .withPropertyValues("ai-friend.knowledge.enabled="+enabled,"ai-friend.knowledge.import.enabled="+importing,
                        "ai-friend.knowledge.maintenance.enabled="+maintenance)
                .withBean(KnowledgeDocumentManagementPort.class,()->documents).withBean(KnowledgeCleanupStatusPort.class,()->cleanup)
                .run(c->{
                    assertThat(c).hasNotFailed();
                    var jwt=Jwt.withTokenValue("test").header("alg","none").subject("test").build();
                    var auth=new JwtAuthenticationToken(jwt,List.of(new SimpleGrantedAuthority("SCOPE_knowledge:manage")));
                    var response=c.getBean(KnowledgeAdminController.class).cleanup(auth);
                    assertThat(response.getBody().data().localScanEnabled()).isEqualTo(expected);
                    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
                    verify(cleanup).read();verifyNoMoreInteractions(cleanup);verifyNoInteractions(documents);
                    assertThat(c).doesNotHaveBean(KnowledgeMaintenanceScheduler.class);
                });
    }
    @Test void defaultOffStillAssemblesDeletionPortWithoutDatabaseAccessOrScheduler() {
        DataSource source=mock(DataSource.class);PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
        new ApplicationContextRunner().withUserConfiguration(KnowledgeManagementConfiguration.class)
                .withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->tx)
                .run(context->{assertThat(context).hasSingleBean(KnowledgeDocumentManagementPort.class);
                    assertThat(context).hasSingleBean(KnowledgeCleanupStatusPort.class);
                    assertThat(context).doesNotHaveBean(KnowledgeImportScheduler.class);verifyNoInteractions(source,tx);});
    }
    @Test void featureOffRejectsImportButDoesNotBlockAuthorizedDeletion() {
        var documents=mock(KnowledgeDocumentManagementPort.class);var registration=mock(KnowledgeImportRegistrationPort.class);
        new ApplicationContextRunner().withUserConfiguration(KnowledgeAdminController.class)
                .withPropertyValues("ai-friend.knowledge.enabled=false","ai-friend.knowledge.import.enabled=false")
                .withBean(KnowledgeDocumentManagementPort.class,()->documents).withBean(KnowledgeImportRegistrationPort.class,()->registration)
                .run(context->{
                    var controller=context.getBean(KnowledgeAdminController.class);
                    var jwt=Jwt.withTokenValue("test").header("alg","none").subject("test").build();
                    var auth=new JwtAuthenticationToken(jwt,List.of(new SimpleGrantedAuthority("SCOPE_knowledge:manage")));
                    assertThatThrownBy(()->controller.submit(auth,new ByteArrayInputStream(new byte[0]))).hasMessage("KNOWLEDGE_IMPORT_DISABLED");
                    UUID id=UUID.randomUUID();assertThat(controller.delete(auth,id,5).getStatusCode().value()).isEqualTo(204);
                    verify(documents).invalidate(id,5);verifyNoInteractions(registration);
                });
    }

    @Test void featureOffRetainsAuthorizedReadOnlyCleanupStatusWithoutPretendingScannerRuns() {
        var documents=mock(KnowledgeDocumentManagementPort.class);
        var cleanup=mock(KnowledgeCleanupStatusPort.class);
        var now=java.time.Instant.parse("2026-09-12T12:00:00Z");
        when(cleanup.read()).thenReturn(new KnowledgeCleanupStatusPort.Snapshot(now,null,now,0,
                KnowledgeCleanupStatusPort.LeaseState.NONE,0,0,0,false,1,1));
        new ApplicationContextRunner().withUserConfiguration(KnowledgeAdminController.class)
                .withPropertyValues("ai-friend.knowledge.enabled=false","ai-friend.knowledge.import.enabled=false")
                .withBean(KnowledgeDocumentManagementPort.class,()->documents).withBean(KnowledgeCleanupStatusPort.class,()->cleanup)
                .run(context->{
                    var jwt=Jwt.withTokenValue("test").header("alg","none").subject("test").build();
                    var auth=new JwtAuthenticationToken(jwt,List.of(new SimpleGrantedAuthority("SCOPE_knowledge:manage")));
                    var response=context.getBean(KnowledgeAdminController.class).cleanup(auth);
                    assertThat(response.getBody().data().localScanEnabled()).isFalse();
                    assertThat(response.getBody().data().state()).isEqualTo(KnowledgeCleanupStatusPort.BacklogState.NO_BACKLOG_OBSERVED);
                    assertThat(response.getBody().data().pendingOverdueAlerts()).isEqualTo(1);
                    assertThat(context).doesNotHaveBean(KnowledgeImportScheduler.class);verify(cleanup).read();verifyNoInteractions(documents);
                });
    }
}
