package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.aifriend.retrieval.application.KnowledgeQuotaPort.Decision;
import com.aifriend.retrieval.application.KnowledgeQuotaPort.Phase;
import com.aifriend.retrieval.application.KnowledgeQuotaPort.Reservation;

/** 受控SQL/锁/提交模拟；不验证真实MySQL语法或数据库隔离。 */
class JdbcKnowledgeQuotaAdapterTest {
    private static UUID id(int n) { return new UUID(0, n); }
    private static KnowledgeQuotaProperties limits(long calls) {
        return new KnowledgeQuotaProperties(true, 2, calls, calls, calls, calls, calls, calls);
    }

    @Test void firstGrantConsumesSixGlobalLaneAndProfileCountersReplayNeverGrantsAgain() throws Exception {
        var db = new Simulation();
        var adapter = db.adapter(limits(10));
        var request = db.request(1, Phase.QUERY_EMBEDDING, 1, 0, "p1");
        assertThat(adapter.reserve(request)).isEqualTo(Decision.GRANTED);
        assertThat(db.counts).hasSize(6);
        assertThat(db.counts.values()).containsOnly(1L);
        assertThat(adapter.reserve(request)).isEqualTo(Decision.DUPLICATE);
        assertThat(db.counts.values()).containsOnly(1L);
    }

    @Test void ownerRateIsIndependentAndDoesNotChargeModelBudget() throws Exception {
        var db = new Simulation();
        var adapter = db.adapter(limits(10));
        assertThat(adapter.reserve(db.request(1, Phase.REQUEST, 1, 0, "LOCAL"))).isEqualTo(Decision.GRANTED);
        assertThat(adapter.reserve(db.request(2, Phase.REQUEST, 1, 0, "LOCAL"))).isEqualTo(Decision.GRANTED);
        assertThat(adapter.reserve(db.request(3, Phase.REQUEST, 1, 0, "LOCAL"))).isEqualTo(Decision.LIMIT_EXCEEDED);
        var other = new Reservation(id(4), Optional.of(id(21)), Phase.REQUEST, "LOCAL", 1, 0, db.now.plusSeconds(8));
        assertThat(adapter.reserve(other)).isEqualTo(Decision.GRANTED);
        assertThat(db.counts).hasSize(2);
        assertThat(db.counts.values()).containsExactlyInAnyOrder(2L, 1L);
    }

    @Test void thirtyConcurrentRequestsAcrossTwoInstancesCannotExceedSharedCap() throws Exception {
        var db = new Simulation();
        var a = db.adapter(limits(5));
        var b = db.adapter(limits(5));
        var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Callable<Decision>>();
            for (int i = 1; i <= 30; i++) {
                int number = i;
                tasks.add(() -> (number % 2 == 0 ? a : b).reserve(db.request(number, Phase.QUERY_EMBEDDING, 1, 0, "p1")));
            }
            var result = pool.invokeAll(tasks, 10, TimeUnit.SECONDS);
            int grants = 0;
            for (var future : result) { if (future.get() == Decision.GRANTED) { grants++; } }
            assertThat(grants).isEqualTo(5);
            assertThat(db.counts.values()).containsOnly(5L);
        } finally { pool.shutdownNow(); }
    }

    @Test void concurrentReplayGrantsOnlyOneExecutionPermit() throws Exception {
        var db = new Simulation();
        var adapter = db.adapter(limits(20));
        var request = db.request(1, Phase.IMPORT_EMBEDDING, 1, 0, "p1");
        var pool = Executors.newFixedThreadPool(3);
        try {
            var tasks = new ArrayList<Callable<Decision>>();
            for (int i = 0; i < 12; i++) { tasks.add(() -> adapter.reserve(request)); }
            var futures = pool.invokeAll(tasks, 10, TimeUnit.SECONDS);
            var values = new ArrayList<Decision>();
            for (var future : futures) { values.add(future.get()); }
            assertThat(values.stream().filter(d -> d == Decision.GRANTED).count()).isEqualTo(1);
            assertThat(values.stream().filter(d -> d == Decision.DUPLICATE).count()).isEqualTo(11);
        } finally { pool.shutdownNow(); }
    }

    @Test void changingProfileOrLaneCannotResetOverallCostBudget() throws Exception {
        var db = new Simulation();
        var adapter = db.adapter(limits(2));
        assertThat(adapter.reserve(db.request(1, Phase.IMPORT_EMBEDDING, 1, 0, "p1"))).isEqualTo(Decision.GRANTED);
        assertThat(adapter.reserve(db.request(2, Phase.QUERY_EMBEDDING, 1, 0, "p2"))).isEqualTo(Decision.GRANTED);
        assertThat(adapter.reserve(db.request(3, Phase.ANSWER_GENERATION, 1, 0, "p3"))).isEqualTo(Decision.LIMIT_EXCEEDED);
    }

    @Test void importLaneCannotConsumeReservedOnlineLaneCapacity() throws Exception {
        var db = new Simulation();
        var adapter = db.adapter(new KnowledgeQuotaProperties(true, 2, 10, 10, 8, 8, 2, 2));
        assertThat(adapter.reserve(db.request(1, Phase.IMPORT_EMBEDDING, 1, 0, "p1"))).isEqualTo(Decision.GRANTED);
        assertThat(adapter.reserve(db.request(2, Phase.IMPORT_EMBEDDING, 1, 0, "p1"))).isEqualTo(Decision.GRANTED);
        assertThat(adapter.reserve(db.request(3, Phase.IMPORT_EMBEDDING, 1, 0, "p1"))).isEqualTo(Decision.LIMIT_EXCEEDED);
        assertThat(adapter.reserve(db.request(4, Phase.QUERY_EMBEDDING, 1, 0, "p1"))).isEqualTo(Decision.GRANTED);
    }

    @Test void hourlyResetDoesNotResetDailyCounterAndDbClockRewindFailsClosed() throws Exception {
        var db = new Simulation();
        var adapter = db.adapter(new KnowledgeQuotaProperties(true, 2, 1, 2, 1, 2, 1, 2));
        assertThat(adapter.reserve(db.request(1, Phase.QUERY_EMBEDDING, 1, 0, "p1"))).isEqualTo(Decision.GRANTED);
        db.now = db.now.plusSeconds(3600);
        assertThat(adapter.reserve(db.request(2, Phase.QUERY_EMBEDDING, 1, 0, "p1"))).isEqualTo(Decision.GRANTED);
        db.now = db.now.plusSeconds(3600);
        assertThat(adapter.reserve(db.request(3, Phase.QUERY_EMBEDDING, 1, 0, "p1"))).isEqualTo(Decision.LIMIT_EXCEEDED);
        db.now = db.now.minusSeconds(1);
        assertThatThrownBy(() -> adapter.reserve(db.request(4, Phase.QUERY_EMBEDDING, 1, 0, "p1")))
                .hasMessage("QUOTA_CLOCK_UNRELIABLE");
    }

    @Test void sameOperationIdentityCannotChangeProfileOwnerOrDeadline() throws Exception {
        var db = new Simulation();
        var adapter = db.adapter(limits(10));
        var original = db.request(1, Phase.QUERY_EMBEDDING, 1, 0, "p1");
        adapter.reserve(original);
        for (var changed : List.of(db.request(1, Phase.QUERY_EMBEDDING, 1, 0, "p2"),
                new Reservation(id(1), Optional.of(id(99)), original.phase(), "p1", 1, 0, original.deadline()),
                new Reservation(id(1), original.ownerId(), original.phase(), "p1", 1, 0, original.deadline().minusSeconds(1)))) {
            assertThatThrownBy(() -> adapter.reserve(changed)).hasMessage("QUOTA_RESERVATION_CONFLICT");
        }
        assertThat(db.counts.values()).containsOnly(1L);
    }

    @Test void exhaustedRequestStaysDeniedEvenIfConfigurationLaterIncreases() throws Exception {
        var db = new Simulation();
        var request = db.request(1, Phase.QUERY_EMBEDDING, 1, 0, "p1");
        assertThat(db.adapter(limits(0)).reserve(request)).isEqualTo(Decision.LIMIT_EXCEEDED);
        assertThat(db.adapter(limits(100)).reserve(request)).isEqualTo(Decision.LIMIT_EXCEEDED);
        assertThat(db.counts).isEmpty();
    }

    @Test void commitResponseLostConservativelyConsumesBudgetAndReplayDoesNotGrant() throws Exception {
        var db = new Simulation();
        var adapter = db.adapter(limits(10));
        var request = db.request(1, Phase.QUERY_EMBEDDING, 1, 0, "p1");
        db.loseCommit = true;
        assertThatThrownBy(() -> adapter.reserve(request)).isInstanceOf(DataAccessResourceFailureException.class);
        db.loseCommit = false;
        assertThat(adapter.reserve(request)).isEqualTo(Decision.DUPLICATE);
        assertThat(db.counts.values()).containsOnly(1L);
    }

    @Test void anyCounterOrLedgerWriteFailureRollsBackAllCharges() throws Exception {
        for (int step = 1; step <= 8; step++) {
            var db = new Simulation();
            db.failAtWrite = step;
            assertThatThrownBy(() -> db.adapter(limits(10)).reserve(db.request(1, Phase.QUERY_EMBEDDING, 1, 0, "p1")))
                    .isInstanceOf(DataAccessResourceFailureException.class);
            assertThat(db.counts).isEmpty();
            assertThat(db.reservations).isEmpty();
        }
    }

    @Test void expiryBeforeStartAndDuringCommitCannotGrant() throws Exception {
        var db = new Simulation();
        var request = db.request(1, Phase.QUERY_EMBEDDING, 1, 0, "p1");
        db.now = request.deadline();
        assertThat(db.adapter(limits(10)).reserve(request)).isEqualTo(Decision.EXPIRED);
        assertThat(db.counts).isEmpty();
        db.expireAtInsert = true;
        assertThatThrownBy(() -> db.adapter(limits(10)).reserve(db.request(2, Phase.QUERY_EMBEDDING, 1, 0, "p1")))
                .hasMessage("QUOTA_RESERVATION_NOT_COMMITTED");
        assertThat(db.counts).isEmpty();
    }

    @Test void disabledQuotaTouchesNeitherDatabaseNorTransaction() throws Exception {
        var db = new Simulation();
        var adapter = db.adapter(new KnowledgeQuotaProperties(false, 0, 0, 0, 0, 0, 0, 0));
        assertThat(adapter.reserve(db.request(1, Phase.REQUEST, 1, 0, "LOCAL"))).isEqualTo(Decision.DISABLED);
        verify(db.transactions, never()).getTransaction(any());
        verifyNoInteractions(db.jdbc);
    }

    @Test void unavailableOrCorruptStorageIsNotSuccessfulReservation() throws Exception {
        var db = new Simulation();
        when(db.jdbc.queryForObject(eq("SELECT UTC_TIMESTAMP(3)"), eq(Timestamp.class)))
                .thenThrow(new DataAccessResourceFailureException("simulated unavailable"));
        assertThatThrownBy(() -> db.adapter(limits(10)).reserve(db.request(1, Phase.QUERY_EMBEDDING, 1, 0, "p1")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(db.counts).isEmpty();
    }

    @Test void finiteAttemptsAndOwnerRulesAreValidatedBeforeStorage() {
        Instant end = Instant.parse("2026-09-10T00:00:08Z");
        assertThatThrownBy(() -> new Reservation(id(1), Optional.of(id(2)), Phase.QUERY_EMBEDDING, "p", 2, 0, end))
                .hasMessage("INVALID_QUOTA_RESERVATION");
        assertThatThrownBy(() -> new Reservation(id(1), Optional.of(id(2)), Phase.ANSWER_GENERATION, "p", 3, 0, end))
                .hasMessage("INVALID_QUOTA_RESERVATION");
        assertThatThrownBy(() -> new Reservation(id(1), Optional.of(id(2)), Phase.IMPORT_EMBEDDING, "p", 1, 0, end))
                .hasMessage("INVALID_QUOTA_RESERVATION");
        assertThatThrownBy(() -> new Reservation(id(1), Optional.empty(), Phase.IMPORT_EMBEDDING, "p", 1, 2000, end))
                .hasMessage("INVALID_QUOTA_RESERVATION");
        assertThatThrownBy(() -> new Reservation(id(1), Optional.of(id(2)), Phase.REQUEST, "not-local", 1, 0, end))
                .hasMessage("INVALID_QUOTA_RESERVATION");
    }

    private static final class Simulation {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        final ReentrantLock lock = new ReentrantLock();
        final Map<String, Long> counts = new HashMap<>();
        final Map<String, Map<String, Object>> reservations = new HashMap<>();
        Instant now = Instant.parse("2026-09-10T00:00:00Z");
        Instant last = now;
        Map<String, Long> oldCounts;
        Map<String, Map<String, Object>> oldReservations;
        Instant oldLast;
        int writes, failAtWrite;
        boolean loseCommit, expireAtInsert;

        @SuppressWarnings({"rawtypes", "unchecked"})
        Simulation() throws Exception {
            when(transactions.getTransaction(any())).thenAnswer(c -> {
                lock.lock(); oldCounts = new HashMap<>(counts); oldReservations = new HashMap<>(reservations); oldLast = last; writes = 0;
                return new SimpleTransactionStatus();
            });
            doAnswer(c -> {
                lock.unlock();
                if (loseCommit) { throw new DataAccessResourceFailureException("simulated commit response lost"); }
                return null;
            }).when(transactions).commit(any());
            doAnswer(c -> {
                counts.clear(); counts.putAll(oldCounts); reservations.clear(); reservations.putAll(oldReservations); last = oldLast;
                lock.unlock(); return null;
            }).when(transactions).rollback(any());
            when(jdbc.queryForObject(eq("SELECT UTC_TIMESTAMP(3)"), eq(Timestamp.class))).thenAnswer(c -> Timestamp.from(now));
            doAnswer(c -> {
                RowMapper mapper = c.getArgument(1);
                return List.of(mapper.mapRow(row(Map.of("last_seen_at", Timestamp.from(last))), 0));
            }).when(jdbc).query(anyString(), any(RowMapper.class));
            doAnswer(c -> {
                String sql = c.getArgument(0);
                RowMapper mapper = c.getArgument(1);
                Object[] args = Arrays.copyOfRange(c.getArguments(), 2, c.getArguments().length);
                assertThat(sql.chars().filter(ch -> ch == '?').count()).isEqualTo(args.length);
                Map<String, Object> data;
                if (sql.contains("knowledge_quota_reservation")) { data = reservations.get(ledger(args)); }
                else {
                    Long count = counts.get(bucket(args));
                    data = count == null ? null : Map.of("used_count", count);
                }
                return data == null ? List.of() : List.of(mapper.mapRow(row(data), 0));
            }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
            doAnswer(c -> {
                String sql = c.getArgument(0);
                Object[] args = Arrays.copyOfRange(c.getArguments(), 1, c.getArguments().length);
                assertThat(sql.chars().filter(ch -> ch == '?').count()).isEqualTo(args.length);
                if (++writes == failAtWrite) { throw new DataAccessResourceFailureException("simulated write failure"); }
                if (sql.startsWith("UPDATE knowledge_quota_control")) { last = ((Timestamp) args[0]).toInstant(); }
                else if (sql.startsWith("INSERT INTO knowledge_quota_bucket")) {
                    counts.put(bucket(new Object[] {args[0], args[2], args[3]}), 1L);
                } else if (sql.startsWith("UPDATE knowledge_quota_bucket")) {
                    String key = bucket(args);
                    if (!counts.get(key).equals(args[3])) { return 0; }
                    counts.put(key, counts.get(key) + 1);
                } else {
                    assertThat(sql).contains("WHERE UTC_TIMESTAMP(3)>=? AND UTC_TIMESTAMP(3)<?");
                    if (expireAtInsert) { return 0; }
                    reservations.put(ledger(args), Map.of("request_digest", args[6], "decision", args[7]));
                }
                return 1;
            }).when(jdbc).update(anyString(), any(Object[].class));
        }

        JdbcKnowledgeQuotaAdapter adapter(KnowledgeQuotaProperties properties) { return new JdbcKnowledgeQuotaAdapter(jdbc, transactions, properties); }
        Reservation request(int operation, Phase phase, int attempt, int sequence, String profile) {
            return new Reservation(id(operation), phase == Phase.IMPORT_EMBEDDING ? Optional.empty() : Optional.of(id(20)),
                    phase, profile, attempt, sequence, now.plusSeconds(phase == Phase.IMPORT_EMBEDDING ? 600 : 8));
        }
        String ledger(Object[] args) { return args[0] + ":" + args[1] + ":" + args[2] + ":" + args[3]; }
        String bucket(Object[] args) { return HexFormat.of().formatHex((byte[]) args[0]) + ":" + args[1] + ":" + args[2]; }
        ResultSet row(Map<String, Object> values) throws Exception {
            var rs = mock(ResultSet.class);
            when(rs.getTimestamp(anyString())).thenAnswer(c -> values.get(c.getArgument(0)));
            when(rs.getBytes(anyString())).thenAnswer(c -> values.get(c.getArgument(0)));
            when(rs.getString(anyString())).thenAnswer(c -> values.get(c.getArgument(0)));
            when(rs.getObject(anyString())).thenAnswer(c -> values.get(c.getArgument(0)));
            return rs;
        }
    }
}
