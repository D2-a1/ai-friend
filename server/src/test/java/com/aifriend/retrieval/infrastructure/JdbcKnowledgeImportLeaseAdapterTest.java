package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.aifriend.retrieval.domain.KnowledgeImportJob;

/**
 * 使用受控锁/时钟/提交故障模拟两个实例。SQL在模拟器中按固定操作解释，
 * 不能代替云端MySQL的实际锁、回滚、约束和SQL执行验证。
 */
class JdbcKnowledgeImportLeaseAdapterTest {
    private static final UUID JOB = new UUID(0, 1);
    private static final UUID TOKEN_A = new UUID(0, 2);
    private static final UUID TOKEN_B = new UUID(0, 3);
    private static final Instant START = Instant.parse("2026-09-09T00:00:00Z");

    @Test void twentyConcurrentClaimsAcrossTwoInstancesProduceOnlyOneHolder() throws Exception {
        var database = new Simulation();
        var first = database.adapter();
        var second = database.adapter();
        var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 20; i++) {
                int n = i;
                tasks.add(() -> (n % 2 == 0 ? first : second)
                        .claim(JOB, new UUID(0, n + 100), Duration.ofSeconds(30)).isPresent());
            }
            var results = pool.invokeAll(tasks, 5, TimeUnit.SECONDS);
            int granted = 0;
            for (var result : results) { if (result.get()) { granted++; } }
            assertThat(granted).isEqualTo(1);
            assertThat(database.job.get("attempts")).isEqualTo(1);
            assertThat(database.committedWrites).isEqualTo(2);
        } finally { pool.shutdownNow(); }
    }

    @Test void replacementAfterExactExpiryRejectsOldWorkerWithoutChangingNewState() throws Exception {
        var database = new Simulation();
        var old = database.adapter().claim(JOB, TOKEN_A, Duration.ofSeconds(1)).orElseThrow();
        database.now = START.plusSeconds(1);
        var replacement = database.adapter().claim(JOB, TOKEN_B, Duration.ofSeconds(30)).orElseThrow();
        assertThat(replacement.job().attempts()).isEqualTo(2);
        assertThatThrownBy(() -> database.adapter().fail(old, KnowledgeImportJob.Failure.TEMPORARY, true))
                .isInstanceOf(ConcurrencyFailureException.class);
        assertThat(database.control.get("lease_token")).isEqualTo(TOKEN_B.toString());
        assertThat(database.job.get("attempts")).isEqualTo(2);
    }

    @Test void failureReleasePersistsBackoffAndDoesNotResetAttemptCounter() throws Exception {
        var database = new Simulation();
        var adapter = database.adapter();
        var claim = adapter.claim(JOB, TOKEN_A, Duration.ofSeconds(30)).orElseThrow();
        var failed = adapter.fail(claim, KnowledgeImportJob.Failure.TEMPORARY, true);
        assertThat(failed.state()).isEqualTo(KnowledgeImportJob.State.PENDING);
        assertThat(database.control.get("lease_token")).isNull();
        assertThat(adapter.claim(JOB, TOKEN_B, Duration.ofSeconds(30))).isEmpty();
        database.now = START.plusSeconds(1);
        assertThat(database.adapter().claim(JOB, TOKEN_B, Duration.ofSeconds(30)).orElseThrow().job().attempts()).isEqualTo(2);
    }

    @Test void failedControlCasRollsBackJobMutationInSimulation() throws Exception {
        var database = new Simulation();
        database.failControlCas = true;
        assertThatThrownBy(() -> database.adapter().claim(JOB, TOKEN_A, Duration.ofSeconds(30)))
                .isInstanceOf(ConcurrencyFailureException.class);
        assertThat(database.job.get("status")).isEqualTo("PENDING");
        assertThat(database.job.get("attempts")).isEqualTo(0);
        assertThat(database.control.get("lease_token")).isNull();
    }

    @Test void lostCommitResponseDoesNotBlindlyClaimAgain() throws Exception {
        var database = new Simulation();
        database.loseCommitResponse = true;
        assertThatThrownBy(() -> database.adapter().claim(JOB, TOKEN_A, Duration.ofSeconds(30)))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(database.job.get("attempts")).isEqualTo(1);
        assertThat(database.control.get("lease_token")).isEqualTo(TOKEN_A.toString());
        // 另一实例只看到已持久租约；没有重试来覆盖“提交结果未知”的状态。
        database.loseCommitResponse = false;
        assertThat(database.adapter().claim(JOB, TOKEN_B, Duration.ofSeconds(30))).isEmpty();
        assertThat(database.committedWrites).isEqualTo(2);
    }

    @Test void taskDeadlineConvergesPendingJobWithoutAcquiringLease() throws Exception {
        var database = new Simulation();
        database.now = START.plusSeconds(600);
        assertThat(database.adapter().claim(JOB, TOKEN_A, Duration.ofSeconds(30))).isEmpty();
        assertThat(database.job.get("status")).isEqualTo("FAILED");
        assertThat(database.job.get("error_code")).isEqualTo("DEADLINE");
        assertThat(database.control.get("lease_token")).isNull();
    }

    @Test void missingDatabaseClockIsFailureNotLocalClockFallback() throws Exception {
        var database = new Simulation();
        database.now = null;
        assertThatThrownBy(() -> database.adapter().claim(JOB, TOKEN_A, Duration.ofSeconds(30)))
                .hasMessage("DATABASE_CLOCK_UNAVAILABLE");
        assertThat(database.job.get("attempts")).isEqualTo(0);
    }

    @Test void fractionalDatabaseTimestampLeaseIsRejectedBeforeTransaction() throws Exception {
        var database = new Simulation();
        assertThatIllegalArgumentException().isThrownBy(() -> database.adapter().claim(JOB, TOKEN_A, Duration.ofNanos(1)));
        assertThat(database.committedWrites).isZero();
        verify(database.transactions, never()).getTransaction(any());
    }

    private static final class Simulation {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        final ReentrantLock lock = new ReentrantLock();
        final Map<String, Object> control = new HashMap<>();
        final Map<String, Object> job = new HashMap<>();
        Map<String, Object> beforeControl;
        Map<String, Object> beforeJob;
        Instant now = START;
        boolean failControlCas;
        boolean loseCommitResponse;
        int pendingWrites;
        int committedWrites;

        @SuppressWarnings({"rawtypes", "unchecked"})
        Simulation() throws Exception {
            control.putAll(Map.of("version", 1L, "corpus_revision", 1L));
            job.putAll(Map.of("id", JOB.toString(), "status", "PENDING", "attempts", 0, "version", 1L,
                    "next_attempt_at", Timestamp.from(START), "deadline", Timestamp.from(START.plusSeconds(600)), "error_code", "NONE"));
            when(transactions.getTransaction(any())).thenAnswer(call -> {
                lock.lock();
                beforeControl = new HashMap<>(control);
                beforeJob = new HashMap<>(job);
                pendingWrites = 0;
                return new SimpleTransactionStatus();
            });
            doAnswer(call -> {
                committedWrites += pendingWrites;
                lock.unlock();
                if (loseCommitResponse) { throw new DataAccessResourceFailureException("simulated commit response lost"); }
                return null;
            }).when(transactions).commit(any());
            doAnswer(call -> {
                control.clear(); control.putAll(beforeControl);
                job.clear(); job.putAll(beforeJob);
                lock.unlock();
                return null;
            }).when(transactions).rollback(any());
            when(jdbc.queryForObject(eq("SELECT UTC_TIMESTAMP(3)"), eq(Timestamp.class)))
                    .thenAnswer(call -> now == null ? null : Timestamp.from(now));
            doAnswer(call -> {
                assertThat((String) call.getArgument(0)).contains("FOR UPDATE");
                RowMapper mapper = call.getArgument(1);
                return List.of(mapper.mapRow(row(control), 0));
            }).when(jdbc).query(anyString(), any(RowMapper.class));
            doAnswer(call -> {
                assertThat((String) call.getArgument(0)).contains("FOR UPDATE");
                RowMapper mapper = call.getArgument(1);
                return List.of(mapper.mapRow(row(job), 0));
            }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
            doAnswer(call -> {
                String sql = call.getArgument(0);
                Object[] args = java.util.Arrays.copyOfRange(call.getArguments(), 1, call.getArguments().length);
                assertThat(sql).contains("version=?");
                if (sql.contains("UPDATE knowledge_import_job")) {
                    if (!args[9].equals(job.get("version"))) { return 0; }
                    job.put("status", args[0]); job.put("attempts", args[1]); job.put("version", args[2]);
                    job.put("lease_token", args[3]); job.put("lease_until", args[4]);
                    job.put("next_attempt_at", args[5]); job.put("error_code", args[6]);
                } else {
                    if (failControlCas || !args[args.length - 1].equals(control.get("version"))) { return 0; }
                    if (sql.contains("lease_job_id=NULL")) {
                        control.remove("lease_job_id"); control.remove("lease_token"); control.remove("lease_until");
                    } else {
                        control.put("lease_job_id", args[0]); control.put("lease_token", args[1]); control.put("lease_until", args[2]);
                    }
                    control.put("version", ((Long) control.get("version")) + 1L);
                }
                pendingWrites++;
                return 1;
            }).when(jdbc).update(anyString(), any(Object[].class));
        }

        JdbcKnowledgeImportLeaseAdapter adapter() { return new JdbcKnowledgeImportLeaseAdapter(jdbc, transactions); }

        ResultSet row(Map<String, Object> values) throws Exception {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
            when(rs.getTimestamp(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
            when(rs.getLong(anyString())).thenAnswer(call -> ((Number) values.getOrDefault(call.getArgument(0), 0L)).longValue());
            when(rs.getInt(anyString())).thenAnswer(call -> ((Number) values.getOrDefault(call.getArgument(0), 0)).intValue());
            return rs;
        }
    }
}
