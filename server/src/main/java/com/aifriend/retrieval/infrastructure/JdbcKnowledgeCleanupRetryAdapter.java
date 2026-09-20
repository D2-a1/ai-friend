package com.aifriend.retrieval.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifriend.retrieval.application.KnowledgeCleanupRetryPort;
import com.aifriend.retrieval.application.KnowledgeCleanupRetryState;

/**
 * RC独立账本：行锁、数据库UTC栅栏、版本CAS及两秒独立事务。
 * 每个方法返回后才可清理或外发通知，不在此事务持锁期间调用外部组件。
 * 不自动注册Bean或线程，构造时不连接数据库。
 * @author Codex
 * @since 1.0.0
 */
public final class JdbcKnowledgeCleanupRetryAdapter implements KnowledgeCleanupRetryPort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    /**
     * 构造但不连接数据库。
     * @param source 数据源
     * @param transactions 同源事务管理器
     */
    public JdbcKnowledgeCleanupRetryAdapter(DataSource source, PlatformTransactionManager transactions) {
        this(new JdbcTemplate(Objects.requireNonNull(source)), transactions);
    }

    JdbcKnowledgeCleanupRetryAdapter(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc);
        jdbc.setQueryTimeout(2);
        transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(2);
    }

    /** {@inheritDoc} */
    @Override public Optional<UUID> acquire() {
        UUID token = UUID.randomUUID();
        Change change = update(state -> state.acquire(token));
        return token.equals(change.after().leaseToken()) ? Optional.of(token) : Optional.empty();
    }

    /** {@inheritDoc} */
    @Override public boolean finish(UUID token, Outcome outcome) {
        Objects.requireNonNull(token); Objects.requireNonNull(outcome);
        return update(state -> state.finish(token, outcome)).changed();
    }

    /** {@inheritDoc} */
    @Override public KnowledgeCleanupRetryState status() { return update(UnaryOperator.identity()).after(); }

    /** {@inheritDoc} */
    @Override public boolean acknowledge(AlertLevel level, long sequence) {
        Objects.requireNonNull(level);
        return update(state -> state.acknowledge(level, sequence)).changed();
    }

    private Change update(UnaryOperator<KnowledgeCleanupRetryState> operation) {
        return Objects.requireNonNull(transaction.execute(status -> {
            var rows = jdbc.query("""
                    SELECT revision,failures,pending_since,next_attempt,last_seen_at,
                        BIN_TO_UUID(lease_token) token,lease_until,escalated,first_failures,
                        first_acknowledged,escalations,escalation_acknowledged
                    FROM knowledge_cleanup_retry WHERE singleton_id=1 FOR UPDATE
                    """, (rs, number) -> new Row(rs.getLong("revision"), new KnowledgeCleanupRetryState(
                        rs.getInt("failures"), instant(rs.getTimestamp("pending_since")),
                        instant(rs.getTimestamp("next_attempt")), instant(rs.getTimestamp("last_seen_at")),
                        rs.getString("token") == null ? null : UUID.fromString(rs.getString("token")),
                        instant(rs.getTimestamp("lease_until")), rs.getBoolean("escalated"),
                        rs.getLong("first_failures"), rs.getLong("first_acknowledged"),
                        rs.getLong("escalations"), rs.getLong("escalation_acknowledged"))));
            if (rows.size() != 1 || rows.get(0).revision() < 1 || rows.get(0).revision() == Long.MAX_VALUE) {
                throw new IllegalStateException("INVALID_CLEANUP_RETRY_ROW");
            }
            Row row = rows.get(0);
            Instant now = instant(jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", Timestamp.class));
            KnowledgeCleanupRetryState observed = row.state().observe(now);
            KnowledgeCleanupRetryState next = Objects.requireNonNull(operation.apply(observed));
            if (!next.equals(row.state())) {
                int count = jdbc.update("""
                        UPDATE knowledge_cleanup_retry SET revision=revision+1,failures=?,pending_since=?,
                            next_attempt=?,last_seen_at=?,lease_token=UUID_TO_BIN(?),lease_until=?,escalated=?,
                            first_failures=?,first_acknowledged=?,escalations=?,escalation_acknowledged=?
                        WHERE singleton_id=1 AND revision=?
                        """, next.failures(), timestamp(next.pendingSince()), timestamp(next.nextAttempt()),
                        timestamp(next.lastSeen()), next.leaseToken() == null ? null : next.leaseToken().toString(),
                        timestamp(next.leaseUntil()), next.escalated(), next.firstFailures(), next.firstAcknowledged(),
                        next.escalations(), next.escalationAcknowledged(), row.revision());
                if (count != 1) { throw new ConcurrencyFailureException("CLEANUP_RETRY_CONFLICT"); }
            }
            return new Change(next, !next.equals(observed));
        }));
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private record Row(long revision, KnowledgeCleanupRetryState state) { }
    private record Change(KnowledgeCleanupRetryState after, boolean changed) { }
}
