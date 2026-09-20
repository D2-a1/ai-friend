package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 实际维护SQL/事务模板配合模拟账本，不验证MySQL执行或真实锁调度。 */
class JdbcKnowledgeQuotaMaintenanceAdapterTest {
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private List<Row> reservations = new ArrayList<>();
    private List<Row> buckets = new ArrayList<>();
    private List<Row> beforeReservations;
    private List<Row> beforeBuckets;
    private Timestamp lastSeen = Timestamp.from(NOW.minusSeconds(1));
    private Timestamp first = Timestamp.from(NOW);
    private Timestamp last = Timestamp.from(NOW.plusMillis(1));
    private int missingControl;
    private int failWrite;
    private int writes;
    private int clockReads;
    private boolean lostControl;
    private JdbcKnowledgeQuotaMaintenanceAdapter adapter;

    @BeforeEach void setup() throws Exception {
        when(transactions.getTransaction(any())).thenAnswer(call -> {
            TransactionDefinition d = call.getArgument(0);
            assertThat(d.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(d.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThat(d.getTimeout()).isEqualTo(2);
            beforeReservations = new ArrayList<>(reservations); beforeBuckets = new ArrayList<>(buckets);
            return new SimpleTransactionStatus();
        });
        doAnswer(call -> { reservations = beforeReservations; buckets = beforeBuckets; return null; })
                .when(transactions).rollback(any());
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(call -> {
            assertThat((String) call.getArgument(0)).contains("knowledge_quota_control", "FOR UPDATE");
            if (missingControl == 1) { return List.of(); }
            ResultSet rs = mock(ResultSet.class); when(rs.getTimestamp("last_seen_at")).thenReturn(lastSeen);
            Object value = ((RowMapper<?>) call.getArgument(1)).mapRow(rs, 0);
            return missingControl == 2 ? java.util.Arrays.asList(value, value) : java.util.Arrays.asList(value);
        });
        when(jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", Timestamp.class))
                .thenAnswer(call -> ++clockReads == 1 ? first : last);
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(call -> {
            if (++writes == failWrite) { throw new DataAccessResourceFailureException("PRIVATE_DB_DETAILS"); }
            String sql = call.getArgument(0);
            if (sql.startsWith("UPDATE")) {
                assertThat(sql).isEqualTo("UPDATE knowledge_quota_control SET last_seen_at=? WHERE singleton_id=1");
                if (lostControl) { return 0; }
                lastSeen = call.getArgument(1); return 1;
            }
            assertThat(sql).contains("expires_at<=?", "LIMIT 128", "ORDER BY expires_at");
            Instant now = ((Timestamp) call.getArgument(1)).toInstant();
            Instant cutoff = ((Timestamp) call.getArgument(2)).toInstant();
            List<Row> target;
            if (sql.contains("knowledge_quota_reservation")) {
                assertThat(sql).contains("deadline<=?"); target = reservations;
            } else {
                assertThat(sql).contains("window_kind='MINUTE'", "INTERVAL 1 MINUTE", "INTERVAL 1 HOUR", "INTERVAL 1 DAY");
                assertThat(call.getArgument(3, Timestamp.class).toInstant()).isEqualTo(cutoff);
                assertThat(call.getArgument(4, Timestamp.class).toInstant()).isEqualTo(cutoff);
                target = buckets;
            }
            List<Row> selected = target.stream().filter(row -> !row.expires.isAfter(now)
                    && !row.boundary.isAfter(cutoff)).limit(128).toList();
            target.removeAll(selected); return selected.size();
        });
        adapter = new JdbcKnowledgeQuotaMaintenanceAdapter(jdbc, transactions);
    }

    @Test void expiredRecordsAreRemovedButActiveOrRetainedRecordsAndControlRemain() {
        var expired = row(NOW.minus(3, ChronoUnit.DAYS), NOW.minusSeconds(1));
        var activeWithBadExpiry = row(NOW.plusSeconds(8), NOW.minus(4, ChronoUnit.DAYS));
        var retained = row(NOW.minusSeconds(1), NOW.minusSeconds(1));
        var expiryNotReached = row(NOW.minus(4, ChronoUnit.DAYS), NOW.plusSeconds(1));
        reservations.addAll(List.of(expired, activeWithBadExpiry, retained, expiryNotReached));
        buckets.addAll(List.of(expired, activeWithBadExpiry, retained, expiryNotReached));
        var result = adapter.sweep();
        assertThat(result.reservations()).isEqualTo(1); assertThat(result.buckets()).isEqualTo(1);
        assertThat(reservations).containsExactly(activeWithBadExpiry, retained, expiryNotReached);
        assertThat(buckets).containsExactly(activeWithBadExpiry, retained, expiryNotReached);
        assertThat(lastSeen).isEqualTo(last);
    }

    @Test void exactlyTwoDayBoundaryIsEligibleButOneMillisecondNewerIsRetained() {
        var cutoff = NOW.minus(2, ChronoUnit.DAYS);
        reservations.addAll(List.of(row(cutoff, NOW), row(cutoff.plusMillis(1), NOW)));
        assertThat(adapter.sweep().reservations()).isEqualTo(1);
        assertThat(reservations).hasSize(1);
    }

    @Test void boundedBatchesMakeProgressAndRepeatedSweepIsIdempotent() {
        for (int i = 0; i < 260; i++) {
            Row row = row(NOW.minus(3, ChronoUnit.DAYS).minusSeconds(i), NOW.minusSeconds(1));
            reservations.add(row); buckets.add(row);
        }
        assertThat(adapter.sweep().reservations()).isEqualTo(128);
        assertThat(adapter.sweep().buckets()).isEqualTo(128);
        assertThat(adapter.sweep().reservations()).isEqualTo(4);
        assertThat(adapter.sweep().reservations()).isZero();
        assertThat(reservations).isEmpty(); assertThat(buckets).isEmpty();
    }

    @Test void clockRollbackBeforeWorkDoesNotDeleteAnything() {
        first = Timestamp.from(NOW.minusSeconds(2));
        assertThatThrownBy(adapter::sweep).hasMessage("QUOTA_CLOCK_UNRELIABLE");
        assertThat(writes).isZero(); verify(transactions).rollback(any());
    }

    @Test void clockRollbackDuringWorkRestoresBothTables() {
        reservations.add(row(NOW.minus(3, ChronoUnit.DAYS), NOW));
        buckets.addAll(reservations); last = Timestamp.from(NOW.minusMillis(1));
        assertThatThrownBy(adapter::sweep).hasMessage("QUOTA_CLOCK_UNRELIABLE");
        assertThat(reservations).hasSize(1); assertThat(buckets).hasSize(1);
        verify(transactions).rollback(any()); verify(transactions, never()).commit(any());
    }

    @Test void missingOrDuplicateControlFailsBeforeWrites() {
        for (int i : List.of(1, 2)) {
            missingControl = i;
            assertThatThrownBy(adapter::sweep).hasMessage("QUOTA_CLOCK_UNRELIABLE");
        }
        assertThat(writes).isZero();
    }

    @Test void unavailableDatabaseClockFailsBeforeDeletion() {
        first = null;
        assertThatThrownBy(adapter::sweep).hasMessage("QUOTA_CLOCK_UNRELIABLE");
        assertThat(writes).isZero();
    }

    @Test void everyWriteFailureRestoresAllRowsWithoutAutomaticRetry() {
        reservations.add(row(NOW.minus(3, ChronoUnit.DAYS), NOW)); buckets.addAll(reservations);
        for (int i = 1; i <= 3; i++) {
            failWrite = i; writes = 0; clockReads = 0;
            assertThatThrownBy(adapter::sweep).isInstanceOf(DataAccessResourceFailureException.class);
            assertThat(writes).isEqualTo(i); assertThat(reservations).hasSize(1); assertThat(buckets).hasSize(1);
        }
        verify(transactions, times(3)).rollback(any());
    }

    @Test void missingControlUpdateCannotCommitDeletion() {
        lostControl = true; reservations.add(row(NOW.minus(3, ChronoUnit.DAYS), NOW));
        assertThatThrownBy(adapter::sweep).hasMessage("QUOTA_CLEANUP_NOT_COMMITTED");
        assertThat(reservations).hasSize(1); verify(transactions).rollback(any());
    }

    private static Row row(Instant boundary, Instant expires) { return new Row(boundary, expires); }
    private record Row(Instant boundary, Instant expires) { }
}
