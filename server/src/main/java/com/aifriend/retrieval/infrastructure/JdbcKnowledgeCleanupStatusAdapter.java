package com.aifriend.retrieval.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifriend.retrieval.application.KnowledgeCleanupStatusPort;

/**
 * 单SQL一致性快照读取：匿名账本与三种积压计数，不锁控制行、不读取任何正文。
 * 计数子查询最多返回10001项，超10000只报告下界；全查询受两秒超时约束。
 * @author Codex
 * @since 1.0.0
 */
public final class JdbcKnowledgeCleanupStatusAdapter implements KnowledgeCleanupStatusPort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    static final String SELECT_STATUS = """
            SELECT r.failures,r.pending_since,r.next_attempt,r.last_seen_at,r.lease_until,
                r.first_failures,r.first_acknowledged,r.escalations,r.escalation_acknowledged,
                UTC_TIMESTAMP(3) observed_at,
                (SELECT COUNT(*) FROM (SELECT v.document_id FROM knowledge_document_version v
                    JOIN knowledge_document d ON d.id=v.document_id
                    WHERE v.status<>'PURGED' AND (d.active_version IS NULL OR d.active_version<>v.version)
                    LIMIT 10001) pending_versions) inactive_versions,
                (SELECT COUNT(*) FROM (SELECT g.id FROM knowledge_index_generation g
                    WHERE NOT EXISTS (SELECT 1 FROM knowledge_index_control c WHERE c.active_generation_id=g.id)
                    LIMIT 10001) pending_generations) inactive_generations,
                (SELECT COUNT(*) FROM (SELECT k.id FROM knowledge_chunk k
                    WHERE NOT EXISTS (SELECT 1 FROM knowledge_generation_chunk m WHERE m.chunk_id=k.id)
                    LIMIT 10001) pending_chunks) unreferenced_chunks
            FROM knowledge_cleanup_retry r WHERE r.singleton_id=1
            """;

    /**
     * 构造时不访问数据库。
     * @param source 数据源
     * @param transactions 同源事务管理器
     */
    public JdbcKnowledgeCleanupStatusAdapter(DataSource source, PlatformTransactionManager transactions) {
        this(new JdbcTemplate(Objects.requireNonNull(source)), transactions);
    }

    JdbcKnowledgeCleanupStatusAdapter(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc);
        jdbc.setQueryTimeout(2);
        transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
        transaction.setReadOnly(true);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(2);
    }

    /** {@inheritDoc} */
    @Override public Snapshot read() {
        return Objects.requireNonNull(transaction.execute(status -> {
            var rows = jdbc.query(SELECT_STATUS, (rs, number) -> map(rs));
            if (rows.size() != 1) { throw unavailable(); }
            return rows.get(0);
        }));
    }

    private static Snapshot map(ResultSet row) throws SQLException {
        Instant observed = requiredTime(row, "observed_at");
        Instant lastSeen = requiredTime(row, "last_seen_at");
        if (observed.isBefore(lastSeen)) { throw unavailable(); }
        Instant pendingSince = instant(row.getTimestamp("pending_since"));
        if (pendingSince != null && pendingSince.isAfter(lastSeen)) { throw unavailable(); }
        long failures = requiredNumber(row, "failures");
        long first = requiredNumber(row, "first_failures");
        long firstAck = requiredNumber(row, "first_acknowledged");
        long escalations = requiredNumber(row, "escalations");
        long escalationAck = requiredNumber(row, "escalation_acknowledged");
        long versions = boundedCount(row, "inactive_versions");
        long generations = boundedCount(row, "inactive_generations");
        long chunks = boundedCount(row, "unreferenced_chunks");
        if (failures > Integer.MAX_VALUE || firstAck > first || escalationAck > escalations
                || escalations > first || (failures > 0 && first == 0)) { throw unavailable(); }
        Instant until = instant(row.getTimestamp("lease_until"));
        if (until != null && (until.isBefore(lastSeen) || until.isAfter(lastSeen.plusSeconds(30)))) { throw unavailable(); }
        LeaseState lease = until == null ? LeaseState.NONE : until.isAfter(observed) ? LeaseState.ACTIVE : LeaseState.EXPIRED;
        try {
            return new Snapshot(observed, pendingSince, requiredTime(row, "next_attempt"),
                    (int) failures, lease, (int) Math.min(versions, 10000), (int) Math.min(generations, 10000),
                    (int) Math.min(chunks, 10000), versions > 10000 || generations > 10000 || chunks > 10000,
                    first - firstAck, escalations - escalationAck);
        } catch (IllegalArgumentException malformed) { throw unavailable(); }
    }

    private static long boundedCount(ResultSet row, String column) throws SQLException {
        long count = requiredNumber(row, column);
        if (count > 10001) { throw unavailable(); }
        return count;
    }

    private static long requiredNumber(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        if (row.wasNull() || value < 0) { throw unavailable(); }
        return value;
    }

    private static Instant requiredTime(ResultSet row, String column) throws SQLException {
        Instant value = instant(row.getTimestamp(column));
        if (value == null) { throw unavailable(); }
        return value;
    }

    private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
    private static IllegalStateException unavailable() { return new IllegalStateException("KNOWLEDGE_CLEANUP_STATUS_UNAVAILABLE"); }
}
