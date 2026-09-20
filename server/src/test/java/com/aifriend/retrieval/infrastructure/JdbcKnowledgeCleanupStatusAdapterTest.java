package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.aifriend.retrieval.application.KnowledgeCleanupStatusPort.BacklogState;
import com.aifriend.retrieval.application.KnowledgeCleanupStatusPort.LeaseState;

/** 实际只读事务与SQL行映射；数据库、时间和行数据均为模拟。 */
class JdbcKnowledgeCleanupStatusAdapterTest {
    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");

    @Test void noHistoryStillKeepsUndeliveredAlertCountsAndDoesNotTouchRetryState() throws Exception {
        var f = new Fixture();
        f.values.put("first_failures", 5L); f.values.put("first_acknowledged", 2L);
        f.values.put("escalations", 4L); f.values.put("escalation_acknowledged", 1L);
        var snapshot = f.adapter.read();
        assertThat(snapshot.state()).isEqualTo(BacklogState.NO_BACKLOG_OBSERVED);
        assertThat(snapshot.firstFailureAt()).isNull();
        assertThat(snapshot.pendingFirstAlerts()).isEqualTo(3);
        assertThat(snapshot.pendingOverdueAlerts()).isEqualTo(3);
        assertThat(snapshot.observedAt()).isEqualTo(NOW);
        verify(f.jdbc).setQueryTimeout(2);
        verify(f.jdbc).query(eq(JdbcKnowledgeCleanupStatusAdapter.SELECT_STATUS), any(RowMapper.class));
        verifyNoMoreInteractions(f.jdbc);
    }

    @Test void anyRetainedHistoryCountsAsPendingEvenWithoutFailedAttempts() throws Exception {
        for (String column : List.of("inactive_versions", "inactive_generations", "unreferenced_chunks")) {
            var f = new Fixture(); f.values.put(column, 1L);
            assertThat(f.adapter.read().state()).isEqualTo(BacklogState.CLEANUP_PENDING);
            assertThat(f.adapter.read().failures()).isZero();
        }
    }

    @Test void exactCapIsExactButOneMoreMarksLowerBoundWithoutUnboundedMaterialRead() throws Exception {
        var f = new Fixture();
        f.values.put("inactive_versions", 10000L);
        assertThat(f.adapter.read().countsTruncated()).isFalse();
        f.values.put("inactive_versions", 10001L);
        var snapshot = f.adapter.read();
        assertThat(snapshot.inactiveVersions()).isEqualTo(10000);
        assertThat(snapshot.countsTruncated()).isTrue();
        assertThat(JdbcKnowledgeCleanupStatusAdapter.SELECT_STATUS.split("LIMIT 10001", -1)).hasSize(4);
        assertThat(JdbcKnowledgeCleanupStatusAdapter.SELECT_STATUS).doesNotContain("lease_token", "original_text", "chunk_text",
                "source_key", "FOR UPDATE", "UPDATE ", "DELETE ", "INSERT ");
    }

    @Test void expiredLeaseIsObservedButNeverRecoveredByGet() throws Exception {
        var f = new Fixture();
        f.values.put("last_seen_at", Timestamp.from(NOW.minusSeconds(30)));
        f.values.put("lease_until", Timestamp.from(NOW));
        var expired = f.adapter.read();
        assertThat(expired.leaseState()).isEqualTo(LeaseState.EXPIRED);
        assertThat(expired.state()).isEqualTo(BacklogState.CLEANUP_PENDING);
        assertThat(expired.failures()).isZero();
        assertThat(expired.firstFailureAt()).isNull();
        f.values.put("last_seen_at", Timestamp.from(NOW));
        f.values.put("lease_until", Timestamp.from(NOW.plusSeconds(30)));
        assertThat(f.adapter.read().leaseState()).isEqualTo(LeaseState.ACTIVE);
        verify(f.jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void overdueBoundaryUsesDatabaseTimeWithoutPretendingAlertWasEmitted() throws Exception {
        var f = new Fixture();
        f.values.put("failures", 1L); f.values.put("first_failures", 1L);
        f.values.put("pending_since", Timestamp.from(NOW.minusSeconds(899)));
        assertThat(f.adapter.read().overdue()).isFalse();
        f.values.put("pending_since", Timestamp.from(NOW.minusSeconds(900)));
        var result = f.adapter.read();
        assertThat(result.overdue()).isTrue();
        assertThat(result.pendingOverdueAlerts()).isZero();
    }

    @Test void missingRowNullFieldBadCountersOrClockRegressionAreUnavailableNeverZero() throws Exception {
        for (var entry : Map.<String,Object>of(
                "first_acknowledged", 1L, "inactive_versions", 10002L, "unreferenced_chunks", -1L,
                "failures", 2147483648L, "last_seen_at", Timestamp.from(NOW.plusMillis(1)),
                "lease_until", Timestamp.from(NOW.plusSeconds(31))).entrySet()) {
            var f = new Fixture(); f.values.put(entry.getKey(), entry.getValue());
            assertThatThrownBy(f.adapter::read).hasMessage("KNOWLEDGE_CLEANUP_STATUS_UNAVAILABLE");
        }
        var missing = new Fixture(); missing.missing = true;
        assertThatThrownBy(missing.adapter::read).hasMessage("KNOWLEDGE_CLEANUP_STATUS_UNAVAILABLE");
        var nullColumn = new Fixture(); nullColumn.values.remove("inactive_generations");
        assertThatThrownBy(nullColumn.adapter::read).hasMessage("KNOWLEDGE_CLEANUP_STATUS_UNAVAILABLE");
        var invalidPending = new Fixture(); invalidPending.values.put("pending_since", Timestamp.from(NOW.minusSeconds(5)));
        assertThatThrownBy(invalidPending.adapter::read).hasMessage("KNOWLEDGE_CLEANUP_STATUS_UNAVAILABLE");
    }

    @Test void constructorNeverAccessesDatabaseOrCreatesWorker() {
        var source = mock(DataSource.class); var transactions = mock(PlatformTransactionManager.class);
        assertThat(new JdbcKnowledgeCleanupStatusAdapter(source, transactions)).isNotNull();
        verifyNoInteractions(source, transactions);
    }

    private static final class Fixture {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        final JdbcKnowledgeCleanupStatusAdapter adapter = new JdbcKnowledgeCleanupStatusAdapter(jdbc, transactions);
        final Map<String,Object> values = new HashMap<>();
        boolean missing, wasNull;

        @SuppressWarnings({"rawtypes", "unchecked"})
        Fixture() throws Exception {
            for (String name : List.of("failures", "first_failures", "first_acknowledged", "escalations", "escalation_acknowledged",
                    "inactive_versions", "inactive_generations", "unreferenced_chunks")) { values.put(name, 0L); }
            for (String name : List.of("observed_at", "last_seen_at", "next_attempt")) { values.put(name, Timestamp.from(NOW)); }
            when(transactions.getTransaction(any())).thenAnswer(call -> {
                TransactionDefinition definition = call.getArgument(0);
                assertThat(definition.isReadOnly()).isTrue();
                assertThat(definition.getTimeout()).isEqualTo(2);
                assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
                assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                return new SimpleTransactionStatus();
            });
            doAnswer(call -> {
                if (missing) { return List.of(); }
                assertThat((String)call.getArgument(0)).isEqualTo(JdbcKnowledgeCleanupStatusAdapter.SELECT_STATUS);
                var row = mock(ResultSet.class);
                when(row.getLong(anyString())).thenAnswer(read -> {
                    Object value = values.get(read.getArgument(0)); wasNull = value == null;
                    return wasNull ? 0L : ((Number)value).longValue();
                });
                when(row.getTimestamp(anyString())).thenAnswer(read -> values.get(read.getArgument(0)));
                when(row.wasNull()).thenAnswer(read -> wasNull);
                return List.of(((RowMapper)call.getArgument(1)).mapRow(row, 0));
            }).when(jdbc).query(anyString(), any(RowMapper.class));
        }
    }
}
