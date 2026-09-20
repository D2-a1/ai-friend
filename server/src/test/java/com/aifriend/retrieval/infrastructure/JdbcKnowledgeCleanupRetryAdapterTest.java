package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.aifriend.retrieval.application.KnowledgeCleanupRetryPort.Outcome.*;
import static com.aifriend.retrieval.application.KnowledgeCleanupRetryPort.AlertLevel.*;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.aifriend.retrieval.application.KnowledgeCleanupRetryState;

/** 实际JDBC映射/事务模板，SQL与时钟均模拟，不证明MySQL语法或锁行为。 */
class JdbcKnowledgeCleanupRetryAdapterTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test void reconstructionKeepsFailureDeadlineAndBothPendingAlertCounters() throws Exception {
        var f = new Fixture();
        var first = f.adapter();
        UUID token = first.acquire().orElseThrow();
        assertThat(first.finish(token, FAILED)).isTrue();
        var restarted = f.adapter();
        assertThat(restarted.acquire()).isEmpty();
        f.now = START.plusSeconds(1);
        assertThat(restarted.finish(restarted.acquire().orElseThrow(), FAILED)).isTrue();
        assertThat(restarted.status().nextAttempt()).isEqualTo(START.plusSeconds(6));
        f.now = START.plusSeconds(900);
        var pending = f.adapter().status();
        assertThat(pending.firstFailures()).isEqualTo(1);
        assertThat(pending.escalations()).isEqualTo(1);
        assertThat(pending.firstAcknowledged()).isZero();
        assertThat(pending.escalationAcknowledged()).isZero();
        assertThat(pending.pendingSince()).isEqualTo(START);
    }

    @Test void twentySimultaneousInstancesHaveOneLeaseUnderSimulatedSerializedTransactions() throws Exception {
        var f = new Fixture();
        var pool = Executors.newFixedThreadPool(20);
        var ready = new CountDownLatch(20);
        var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Optional<UUID>>>();
            for (int i = 0; i < 20; i++) {
                var adapter = f.adapter();
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("TEST_START_TIMEOUT"); }
                    return adapter.acquire();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var granted = new ArrayList<UUID>();
            for (var future : futures) { future.get(5, TimeUnit.SECONDS).ifPresent(granted::add); }
            assertThat(granted).containsExactly(f.stored.leaseToken());
            assertThat(f.writes).hasSize(1);
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test void expiredLeasePersistsFailureBeforeAnyNewAttemptAndRejectsOldCompletion() throws Exception {
        var f = new Fixture(); var first = f.adapter();
        UUID old = first.acquire().orElseThrow();
        f.now = START.plusSeconds(30);
        assertThat(f.adapter().finish(old, DRAINED)).isFalse();
        assertThat(f.stored.failures()).isEqualTo(1);
        assertThat(f.stored.nextAttempt()).isEqualTo(f.now.plusSeconds(1));
        assertThat(f.adapter().acquire()).isEmpty();
        f.now = f.now.plusSeconds(1);
        UUID replacement = f.adapter().acquire().orElseThrow();
        assertThat(first.finish(old, FAILED)).isFalse();
        assertThat(f.stored.leaseToken()).isEqualTo(replacement);
    }

    @Test void cleanupRecoveryDoesNotEraseUndeliveredAlertsAndAcknowledgementsAreMonotonic() throws Exception {
        var f = new Fixture(); var adapter = f.adapter();
        adapter.finish(adapter.acquire().orElseThrow(), FAILED);
        f.now = START.plusSeconds(900);
        adapter.finish(adapter.acquire().orElseThrow(), DRAINED);
        assertThat(f.stored.pendingSince()).isNull();
        assertThat(f.stored.firstFailures()).isEqualTo(1);
        assertThat(f.stored.escalations()).isEqualTo(1);
        assertThat(adapter.acknowledge(FIRST_FAILURE, 2)).isFalse();
        assertThat(adapter.acknowledge(FIRST_FAILURE, 1)).isTrue();
        assertThat(adapter.acknowledge(FIRST_FAILURE, 1)).isFalse();
        assertThat(f.stored.escalationAcknowledged()).isZero();
        assertThat(adapter.acknowledge(OVERDUE, 1)).isTrue();
        assertThat(f.adapter().status().escalationAcknowledged()).isEqualTo(1);
    }

    @Test void rollbackOfFailureWriteLeavesLeaseToRecoverInsteadOfPretendingDrained() throws Exception {
        var f = new Fixture(); var adapter = f.adapter();
        UUID token = adapter.acquire().orElseThrow();
        f.failWriteAfter = true;
        assertThatThrownBy(() -> adapter.finish(token, FAILED)).hasMessage("SIMULATED_WRITE_FAILURE");
        assertThat(f.stored.leaseToken()).isEqualTo(token);
        assertThat(f.stored.failures()).isZero();
        f.failWriteAfter = false;
        f.now = START.plusSeconds(30);
        assertThat(f.adapter().status().firstFailures()).isEqualTo(1);
    }

    @Test void unknownCommitIsNotRetriedAndFreshInstanceObservesAlreadyCommittedFailure() throws Exception {
        var f = new Fixture(); var adapter = f.adapter();
        UUID token = adapter.acquire().orElseThrow();
        f.failCommitAfter = true;
        assertThatThrownBy(() -> adapter.finish(token, FAILED)).hasMessage("SIMULATED_COMMIT_UNKNOWN");
        assertThat(f.stored.failures()).isEqualTo(1);
        assertThat(f.writes).hasSize(2);
        f.failCommitAfter = false;
        assertThat(f.adapter().finish(token, FAILED)).isFalse();
        assertThat(f.stored.failures()).isEqualTo(1);
        assertThat(f.writes).hasSize(2);
    }

    @Test void missingRowBackwardsClockOrCasConflictNeverCreatesReplacementOrClaimsSuccess() throws Exception {
        var f = new Fixture(); var adapter = f.adapter();
        f.missing = true;
        assertThatThrownBy(adapter::acquire).hasMessage("INVALID_CLEANUP_RETRY_ROW");
        f.missing = false; f.now = START.minusMillis(1);
        assertThatThrownBy(adapter::acquire).hasMessage("CLEANUP_CLOCK_UNRELIABLE");
        f.now = START; f.conflict = true;
        assertThatThrownBy(adapter::acquire).isInstanceOf(ConcurrencyFailureException.class);
        assertThat(f.stored.leaseToken()).isNull();
        assertThat(f.writes).allMatch(sql -> sql.startsWith("UPDATE knowledge_cleanup_retry"));
    }

    @Test void constructorDoesNotConnectDatabaseOrRegisterScheduler() {
        DataSource source = mock(DataSource.class);
        var adapter = new JdbcKnowledgeCleanupRetryAdapter(source, mock(PlatformTransactionManager.class));
        assertThat(adapter).isNotNull();
        verifyNoInteractions(source);
        assertThat(JdbcKnowledgeCleanupRetryAdapter.class.getAnnotations()).isEmpty();
    }

    @Test void migrationIsAdditiveAndKeepsRetryAndAlertFactsWithoutPrivateColumns() throws Exception {
        String ddl = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/db/migration/V39__knowledge_cleanup_retry.sql"));
        assertThat(ddl).contains("CREATE TABLE knowledge_cleanup_retry", "INTERVAL 30 SECOND", "INTERVAL 15 MINUTE",
                "first_acknowledged<=first_failures", "escalation_acknowledged<=escalations", "failures>0");
        assertThat(ddl).doesNotContain("DROP ", "DELETE ", "ALTER ", "owner_user_id", "document_id", "original_text", "api_key");
    }

    static final class Fixture {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        final ReentrantLock lock = new ReentrantLock();
        final List<String> writes = new ArrayList<>();
        KnowledgeCleanupRetryState stored = KnowledgeCleanupRetryState.initial(START), before;
        long revision = 1, beforeRevision;
        Instant now = START;
        boolean missing, conflict, failWriteAfter, failCommitAfter;

        @SuppressWarnings({"rawtypes", "unchecked"})
        Fixture() throws Exception {
            when(transactions.getTransaction(any())).thenAnswer(call -> {
                TransactionDefinition definition = call.getArgument(0);
                assertThat(definition.getTimeout()).isEqualTo(2);
                assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
                lock.lock(); before = stored; beforeRevision = revision;
                return new SimpleTransactionStatus();
            });
            doAnswer(call -> {
                lock.unlock();
                if (failCommitAfter) { throw new IllegalStateException("SIMULATED_COMMIT_UNKNOWN"); }
                return null;
            }).when(transactions).commit(any());
            doAnswer(call -> {
                stored = before; revision = beforeRevision; lock.unlock(); return null;
            }).when(transactions).rollback(any());
            doAnswer(call -> {
                assertThat(lock.isHeldByCurrentThread()).isTrue();
                assertThat((String) call.getArgument(0)).contains("WHERE singleton_id=1 FOR UPDATE");
                if (missing) { return List.of(); }
                var rs = mock(ResultSet.class);
                when(rs.getLong("revision")).thenReturn(revision);
                when(rs.getInt("failures")).thenReturn(stored.failures());
                when(rs.getTimestamp("pending_since")).thenReturn(ts(stored.pendingSince()));
                when(rs.getTimestamp("next_attempt")).thenReturn(ts(stored.nextAttempt()));
                when(rs.getTimestamp("last_seen_at")).thenReturn(ts(stored.lastSeen()));
                when(rs.getTimestamp("lease_until")).thenReturn(ts(stored.leaseUntil()));
                when(rs.getString("token")).thenReturn(stored.leaseToken() == null ? null : stored.leaseToken().toString());
                when(rs.getBoolean("escalated")).thenReturn(stored.escalated());
                when(rs.getLong("first_failures")).thenReturn(stored.firstFailures());
                when(rs.getLong("first_acknowledged")).thenReturn(stored.firstAcknowledged());
                when(rs.getLong("escalations")).thenReturn(stored.escalations());
                when(rs.getLong("escalation_acknowledged")).thenReturn(stored.escalationAcknowledged());
                return List.of(((RowMapper) call.getArgument(1)).mapRow(rs, 0));
            }).when(jdbc).query(anyString(), any(RowMapper.class));
            when(jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", Timestamp.class)).thenAnswer(call -> {
                assertThat(lock.isHeldByCurrentThread()).isTrue(); return ts(now);
            });
            doAnswer(call -> {
                String sql = call.getArgument(0); writes.add(sql);
                assertThat(sql).contains("revision=revision+1", "WHERE singleton_id=1 AND revision=?");
                assertThat((Long) call.getArgument(12)).isEqualTo(revision);
                if (conflict) { return 0; }
                String token = call.getArgument(5);
                stored = new KnowledgeCleanupRetryState(call.getArgument(1), instant(call.getArgument(2)),
                        instant(call.getArgument(3)), instant(call.getArgument(4)), token == null ? null : UUID.fromString(token),
                        instant(call.getArgument(6)), call.getArgument(7), call.getArgument(8), call.getArgument(9),
                        call.getArgument(10), call.getArgument(11));
                revision++;
                if (failWriteAfter) { throw new IllegalStateException("SIMULATED_WRITE_FAILURE"); }
                return 1;
            }).when(jdbc).update(anyString(), any(Object[].class));
        }

        JdbcKnowledgeCleanupRetryAdapter adapter() { return new JdbcKnowledgeCleanupRetryAdapter(jdbc, transactions); }
        private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
        private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    }
}
