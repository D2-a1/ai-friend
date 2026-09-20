package com.aifriend.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.aifriend.consent.application.ConsentRevocationCleanupHandler;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.consent.infrastructure.CompositeConsentRevocationCleanupAdapter;
import com.aifriend.knowledge.application.GraphOwnerCleanupPort;
import com.aifriend.knowledge.application.GraphProjectionException;
import com.aifriend.knowledge.application.ContactGraphQueryService;

/** 模拟SQL和回滚，不声称验证了真实MySQL锁。 */
class GraphOwnerCleanupTest {
    private static final UUID OWNER = new UUID(0, 1);
    private static final UUID OTHER = new UUID(0, 2);
    private static final List<String> TABLES = List.of("knowledge_graph_edge", "knowledge_graph_node", "knowledge_graph_snapshot");
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private Map<String, Long> rows = new HashMap<>();
    private Map<String, Long> before;
    private final List<String> statements = new ArrayList<>();
    private int failWrite;
    private int writeCount;
    private String retainedTable;
    private boolean absentOwner;
    private boolean wrongOwner;
    private JdbcGraphOwnerCleanupAdapter adapter;

    @BeforeEach void setup() throws Exception {
        for (String table : TABLES) {
            rows.put(OWNER + table, 2L); rows.put(OTHER + table, 3L);
        }
        when(transactions.getTransaction(any())).thenAnswer(call -> {
            TransactionDefinition definition = call.getArgument(0);
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
            assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThat(definition.getTimeout()).isEqualTo(2);
            assertThat(definition.isReadOnly()).isFalse();
            before = new HashMap<>(rows); return new SimpleTransactionStatus();
        });
        doAnswer(call -> { rows = new HashMap<>(before); return null; }).when(transactions).rollback(any());
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0); statements.add(sql);
            assertThat(sql).contains("FROM app_user", "WHERE id=UUID_TO_BIN(?)", "FOR UPDATE");
            if (absentOwner) { return List.of(); }
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("id")).thenReturn(wrongOwner ? OTHER.toString() : call.getArgument(2));
            return List.of(((RowMapper<?>) call.getArgument(1)).mapRow(rs, 0));
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0); statements.add(sql);
            String table = table(sql); String key = call.getArgument(1) + table;
            if (++writeCount == failWrite) { throw new DataAccessResourceFailureException("SECRET_DATABASE_DETAIL"); }
            long prior = rows.getOrDefault(key, 0L);
            if (!table.equals(retainedTable)) { rows.put(key, 0L); }
            return (int) prior;
        });
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0); statements.add(sql);
            return rows.getOrDefault(call.getArgument(2) + table(sql), 0L);
        });
        adapter = new JdbcGraphOwnerCleanupAdapter(jdbc, transactions);
    }

    @Test void deletesOnlyRequestedOwnerInForeignKeyOrderWithoutReadingDamagedHeader() {
        assertThat(adapter.purgeOwner(OWNER)).isEqualTo(6);
        assertThat(statements.get(0)).contains("FOR UPDATE");
        assertThat(statements.subList(1, 4)).containsExactlyElementsOf(TABLES.stream()
                .map(table -> "DELETE FROM " + table + " WHERE owner_user_id=UUID_TO_BIN(?)").toList());
        assertThat(statements).noneMatch(sql -> sql.contains("source_digest") || sql.contains("generation"));
        for (String table : TABLES) {
            assertThat(rows.get(OWNER + table)).isZero(); assertThat(rows.get(OTHER + table)).isEqualTo(3);
        }
    }

    @Test void repeatAndMissingAccountOrphansAreCleanedWithoutCurrentConsent() {
        absentOwner = true;
        assertThat(adapter.purgeOwner(OWNER)).isEqualTo(6);
        assertThat(adapter.purgeOwner(OWNER)).isZero();
    }

    @Test void eachDeleteFailureRollsBackAndDoesNotRetryOrLeakDatabaseDetails() {
        for (int point = 1; point <= 3; point++) {
            failWrite = point; writeCount = 0;
            assertThatThrownBy(() -> adapter.purgeOwner(OWNER)).isInstanceOf(GraphProjectionException.class)
                    .hasMessage("STORAGE_UNAVAILABLE").hasNoCause();
            assertThat(writeCount).isEqualTo(point);
            for (String table : TABLES) { assertThat(rows.get(OWNER + table)).isEqualTo(2); }
        }
        verify(transactions, times(3)).rollback(any()); verify(transactions, never()).commit(any());
    }

    @Test void eachResidualTableFailsClearanceAndRollsBack() {
        for (String table : TABLES) {
            retainedTable = table;
            assertThatThrownBy(() -> adapter.purgeOwner(OWNER)).hasMessage("INVALID");
            for (String restored : TABLES) { assertThat(rows.get(OWNER + restored)).isEqualTo(2); }
        }
    }

    @Test void wrongAccountLockResultStopsBeforeAnyDeletion() {
        wrongOwner = true;
        assertThatThrownBy(() -> adapter.purgeOwner(OWNER)).hasMessage("INVALID");
        assertThat(writeCount).isZero();
    }

    @Test void nullOwnerStopsBeforeDatabaseAccess() {
        assertThatNullPointerException().isThrownBy(() -> adapter.purgeOwner(null));
        verifyNoInteractions(jdbc, transactions);
    }

    @Test void onlyGraphRevocationInvokesOwnerCleanup() {
        var port = mock(GraphOwnerCleanupPort.class);
        var handler = new GraphConsentRevocationHandler(port);
        for (ConsentType type : ConsentType.values()) { handler.cleanup(OWNER, type, Instant.EPOCH); }
        verify(port).purgeOwner(OWNER); verifyNoMoreInteractions(port);
    }

    @Test void cleanupFailurePropagatesThroughExistingConsentComposite() {
        var port = mock(GraphOwnerCleanupPort.class);
        var later = mock(ConsentRevocationCleanupHandler.class);
        var failure = new GraphProjectionException(GraphProjectionException.Kind.STORAGE_UNAVAILABLE);
        when(port.purgeOwner(OWNER)).thenThrow(failure);
        var composite = new CompositeConsentRevocationCleanupAdapter(List.of(new GraphConsentRevocationHandler(port), later));
        assertThatThrownBy(() -> composite.cleanup(OWNER, ConsentType.CONTACT_GRAPH, Instant.EPOCH)).isSameAs(failure);
        verifyNoInteractions(later);
    }

    @Test void cleanupBeansRemainAvailableWithAllFeaturesOffWithoutConnecting() {
        var source = mock(DataSource.class);
        new ApplicationContextRunner().withUserConfiguration(JdbcGraphOwnerCleanupAdapter.class,
                GraphConsentRevocationHandler.class, KnowledgeGraphConfiguration.class)
                .withBean(DataSource.class, () -> source)
                .withBean(PlatformTransactionManager.class, () -> transactions)
                .withPropertyValues("ai-friend.knowledge.enabled=false", "ai-friend.knowledge.graph-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(GraphOwnerCleanupPort.class)
                            .hasSingleBean(GraphConsentRevocationHandler.class).doesNotHaveBean(ContactGraphQueryService.class);
                    verifyNoInteractions(source, transactions);
                });
    }

    @Test void graphSqlUsesMysqlBinaryUuidConversionNotAnInventedFunction() throws Exception {
        for (String file : List.of("knowledge/infrastructure/JdbcKnowledgeGraphAdapter.java",
                "knowledge/infrastructure/JdbcGraphOwnerCleanupAdapter.java",
                "contact/infrastructure/ContactGraphSourceAdapter.java")) {
            String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/aifriend/" + file));
            assertThat(source).contains("BIN_TO_UUID(").doesNotContain("UUID_FROM_BIN(");
        }
    }

    private String table(String sql) {
        assertThat(sql).contains("WHERE owner_user_id=UUID_TO_BIN(?)");
        return TABLES.stream().filter(table -> sql.contains("FROM " + table + " ")).findFirst().orElseThrow();
    }
}
