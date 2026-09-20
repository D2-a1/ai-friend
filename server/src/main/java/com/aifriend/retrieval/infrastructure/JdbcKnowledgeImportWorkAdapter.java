package com.aifriend.retrieval.infrastructure;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifriend.retrieval.application.KnowledgeImportLeasePort.Claim;
import com.aifriend.retrieval.application.KnowledgeImportWorkPort;

/**
 * 后台待办有界读取及外发前租约复验，禁止本机墙钟延长DB租约。
 * @author codex
 * @since 1.0.0
 */
public final class JdbcKnowledgeImportWorkAdapter implements KnowledgeImportWorkPort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    /**
     * 使用独立只读短事务，不占用旧任务线程池。
     * @param source 受控数据源
     * @param transactions 同数据源事务管理器
     */
    public JdbcKnowledgeImportWorkAdapter(DataSource source, PlatformTransactionManager transactions) {
        this(new JdbcTemplate(Objects.requireNonNull(source, "source")), transactions);
        jdbc.setQueryTimeout(2);
    }
    JdbcKnowledgeImportWorkAdapter(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transaction = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setReadOnly(true);
        transaction.setTimeout(2);
    }

    /** {@inheritDoc} */
    @Override public List<UUID> due(int limit) {
        if (limit < 1 || limit > 10) { throw new IllegalArgumentException("INVALID_WORK_SCAN_LIMIT"); }
        return transaction.execute(status -> {
            var ids = jdbc.query("""
                    SELECT BIN_TO_UUID(id) id FROM knowledge_import_job
                    WHERE (status='PENDING' AND (next_attempt_at<=UTC_TIMESTAMP(3) OR deadline<=UTC_TIMESTAMP(3)))
                       OR (status='PROCESSING' AND lease_until<=UTC_TIMESTAMP(3))
                    ORDER BY deadline,id LIMIT ?
                    """, (rs, row) -> UUID.fromString(rs.getString("id")), limit);
            if (ids.size() > limit || ids.stream().distinct().count() != ids.size()) {
                throw new IllegalArgumentException("INVALID_WORK_SCAN");
            }
            return List.copyOf(ids);
        });
    }

    /** {@inheritDoc} */
    @Override public Duration remaining(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        return transaction.execute(status -> {
            var rows = jdbc.query("""
                    SELECT LEAST(TIMESTAMPDIFF(MICROSECOND,UTC_TIMESTAMP(3),c.lease_until),
                                 TIMESTAMPDIFF(MICROSECOND,UTC_TIMESTAMP(3),j.lease_until),
                                 TIMESTAMPDIFF(MICROSECOND,UTC_TIMESTAMP(3),j.deadline)) remaining_micros
                    FROM knowledge_index_control c JOIN knowledge_import_job j ON j.id=c.lease_job_id
                    WHERE c.singleton_id=1 AND c.version=? AND c.corpus_revision=?
                      AND j.id=UUID_TO_BIN(?) AND j.version=? AND j.status='PROCESSING'
                      AND c.lease_token=UUID_TO_BIN(?) AND j.lease_token=c.lease_token
                    """, (rs, row) -> {
                        Long micros = (Long) rs.getObject("remaining_micros");
                        if (micros == null || micros <= 0 || micros > 300_000_000L) {
                            throw new ConcurrencyFailureException("IMPORT_LEASE_LOST");
                        }
                        return Duration.ofNanos(micros * 1000);
                    }, claim.controlVersion(), claim.corpusRevision(), claim.job().id().toString(), claim.job().version(),
                    claim.job().leaseToken().orElseThrow().toString());
            if (rows.size() != 1) { throw new ConcurrencyFailureException("IMPORT_LEASE_LOST"); }
            return rows.get(0);
        });
    }
}
