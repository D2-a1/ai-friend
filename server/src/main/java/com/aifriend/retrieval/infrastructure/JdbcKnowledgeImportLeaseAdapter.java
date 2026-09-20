package com.aifriend.retrieval.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifriend.retrieval.application.KnowledgeImportLeasePort;
import com.aifriend.retrieval.domain.KnowledgeImportJob;

/**
 * 持久全局租约：先锁唯一控制行，再锁job，避免每实例各自认领。
 * 不执行外部请求，不自动重试提交结果未知的事务；未默认装配。
 * @author codex
 * @since 1.0.0
 */
public final class JdbcKnowledgeImportLeaseAdapter implements KnowledgeImportLeasePort {
    private static final String CONTROL = """
            SELECT version, corpus_revision, BIN_TO_UUID(lease_job_id) lease_job_id,
                   BIN_TO_UUID(lease_token) lease_token, lease_until
            FROM knowledge_index_control WHERE singleton_id=1 FOR UPDATE
            """;
    private static final String JOB = """
            SELECT BIN_TO_UUID(id) id, status, attempts, version, BIN_TO_UUID(lease_token) lease_token,
                   lease_until, next_attempt_at, deadline, error_code
            FROM knowledge_import_job WHERE id=UUID_TO_BIN(?) FOR UPDATE
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    /**
     * 独立SQL超时设置，不修改旧业务模板。
     * @param source 受控数据源
     * @param transactions 同数据源事务管理器
     */
    public JdbcKnowledgeImportLeaseAdapter(DataSource source, PlatformTransactionManager transactions) {
        this(new JdbcTemplate(Objects.requireNonNull(source, "source")), transactions);
        jdbc.setQueryTimeout(2);
    }

    JdbcKnowledgeImportLeaseAdapter(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transaction = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(2);
    }

    /** {@inheritDoc} */
    @Override public Optional<Claim> claim(UUID jobId, UUID token, Duration duration) {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(token, "token");
        if (duration == null || duration.compareTo(Duration.ofMillis(1)) < 0
                || duration.compareTo(Duration.ofMinutes(5)) > 0 || duration.toNanos() % 1_000_000 != 0) {
            throw new IllegalArgumentException("INVALID_IMPORT_LEASE");
        }
        return transaction.execute(status -> {
            var control = control();
            Instant now = databaseNow();
            if (control.until() != null && now.isBefore(control.until())) { return Optional.empty(); }
            if (token.equals(control.token())) { throw new IllegalArgumentException("IMPORT_TOKEN_REUSED"); }
            Optional<KnowledgeImportJob> existing = job(jobId);
            if (existing.isEmpty()) { return Optional.empty(); }
            var old = existing.orElseThrow();
            var expired = old.expire(now);
            if (expired != old) {
                save(old, expired, now);
                if (jobId.equals(control.jobId())) { release(control); }
                return Optional.empty();
            }
            if ((old.state() != KnowledgeImportJob.State.PENDING && old.state() != KnowledgeImportJob.State.PROCESSING)
                    || now.isBefore(old.nextAttemptAt())
                    || old.leaseUntil().filter(now::isBefore).isPresent()) {
                return Optional.empty();
            }
            var claimed = old.claim(now, token, duration);
            save(old, claimed, now);
            int count = jdbc.update("""
                    UPDATE knowledge_index_control SET lease_job_id=UUID_TO_BIN(?), lease_token=UUID_TO_BIN(?),
                           lease_until=?, version=version+1 WHERE singleton_id=1 AND version=?
                    """, jobId.toString(), token.toString(), Timestamp.from(claimed.leaseUntil().orElseThrow()), control.version());
            requireOne(count);
            return Optional.of(new Claim(claimed, Math.addExact(control.version(), 1), control.revision()));
        });
    }

    /** {@inheritDoc} */
    @Override public KnowledgeImportJob fail(Claim claim, KnowledgeImportJob.Failure reason, boolean retryable) {
        Objects.requireNonNull(claim, "claim");
        return transaction.execute(status -> {
            var control = control();
            Instant now = databaseNow();
            UUID token = claim.job().leaseToken().orElseThrow();
            if (!claim.job().id().equals(control.jobId()) || !token.equals(control.token())
                    || control.until() == null || !now.isBefore(control.until())) {
                throw new ConcurrencyFailureException("IMPORT_LEASE_LOST");
            }
            var current = job(claim.job().id()).orElseThrow(() -> new ConcurrencyFailureException("IMPORT_LEASE_LOST"));
            if (current.version() != claim.job().version()) {
                throw new ConcurrencyFailureException("IMPORT_LEASE_LOST");
            }
            var failed = current.fail(now, token, reason, retryable);
            save(current, failed, now);
            release(control);
            return failed;
        });
    }

    private Control control() {
        List<Control> rows = jdbc.query(CONTROL, (rs, row) -> {
            long version = rs.getLong("version");
            long revision = rs.getLong("corpus_revision");
            UUID job = uuid(rs.getString("lease_job_id"));
            UUID token = uuid(rs.getString("lease_token"));
            Timestamp time = rs.getTimestamp("lease_until");
            if (version < 1 || revision < 0 || (job == null) != (token == null) || (job == null) != (time == null)) {
                throw new IllegalArgumentException("INVALID_IMPORT_CONTROL");
            }
            return new Control(version, revision, job, token, time == null ? null : time.toInstant());
        });
        if (rows.size() != 1) { throw new IllegalArgumentException("INVALID_IMPORT_CONTROL"); }
        return rows.get(0);
    }

    private Optional<KnowledgeImportJob> job(UUID id) {
        List<KnowledgeImportJob> rows = jdbc.query(JOB, this::mapJob, id.toString());
        if (rows.size() > 1) { throw new IllegalArgumentException("INVALID_IMPORT_JOB"); }
        return rows.stream().findFirst();
    }

    private KnowledgeImportJob mapJob(ResultSet rs, int row) throws SQLException {
        Timestamp until = rs.getTimestamp("lease_until");
        return new KnowledgeImportJob(UUID.fromString(rs.getString("id")),
                KnowledgeImportJob.State.valueOf(rs.getString("status")), rs.getInt("attempts"), rs.getLong("version"),
                Optional.ofNullable(uuid(rs.getString("lease_token"))),
                Optional.ofNullable(until == null ? null : until.toInstant()),
                requiredTime(rs, "next_attempt_at"), requiredTime(rs, "deadline"),
                KnowledgeImportJob.Failure.valueOf(rs.getString("error_code")));
    }

    private Instant databaseNow() {
        Timestamp time = jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", Timestamp.class);
        if (time == null) { throw new IllegalStateException("DATABASE_CLOCK_UNAVAILABLE"); }
        return time.toInstant();
    }

    private void save(KnowledgeImportJob old, KnowledgeImportJob next, Instant now) {
        int count = jdbc.update("""
                UPDATE knowledge_import_job SET status=?, attempts=?, version=?, lease_token=UUID_TO_BIN(?),
                       lease_until=?, next_attempt_at=?, error_code=?, updated_at=?
                WHERE id=UUID_TO_BIN(?) AND version=?
                """, next.state().name(), next.attempts(), next.version(),
                next.leaseToken().map(UUID::toString).orElse(null),
                next.leaseUntil().map(Timestamp::from).orElse(null), Timestamp.from(next.nextAttemptAt()),
                next.failure().name(), Timestamp.from(now), next.id().toString(), old.version());
        requireOne(count);
    }

    private void release(Control control) {
        int count = jdbc.update("""
                UPDATE knowledge_index_control SET lease_job_id=NULL, lease_token=NULL, lease_until=NULL,
                       version=version+1 WHERE singleton_id=1 AND version=?
                """, control.version());
        requireOne(count);
    }

    private void requireOne(int count) {
        if (count != 1) { throw new ConcurrencyFailureException("IMPORT_STATE_CONFLICT"); }
    }

    private UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
    private Instant requiredTime(ResultSet rs, String column) throws SQLException {
        Timestamp time = rs.getTimestamp(column);
        if (time == null) { throw new IllegalArgumentException("INVALID_IMPORT_TIME"); }
        return time.toInstant();
    }
    private record Control(long version, long revision, UUID jobId, UUID token, Instant until) { }
}
