package com.aifriend.retrieval.infrastructure;

import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.aifriend.retrieval.application.KnowledgeQuotaMaintenancePort;

/**
 * 仅回收过期至少两天的调用期限/窗口，保留跨实例时钟栅栏和有效额度。
 * 与reserve锁同一控制行，在独立两秒事务内执行；不读取功能开关。
 * @author Codex
 * @since 1.0.0
 */
public final class JdbcKnowledgeQuotaMaintenanceAdapter implements KnowledgeQuotaMaintenancePort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    /**
     * 创建构造时不连接数据库的维护器。
     * @param source 数据源
     * @param transactions 同源事务管理器
     */
    public JdbcKnowledgeQuotaMaintenanceAdapter(DataSource source, PlatformTransactionManager transactions) {
        this(new JdbcTemplate(Objects.requireNonNull(source)), transactions);
        jdbc.setQueryTimeout(2);
    }

    JdbcKnowledgeQuotaMaintenanceAdapter(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc);
        transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(2);
    }

    /** {@inheritDoc} */
    @Override public Cleanup sweep() {
        return Objects.requireNonNull(transaction.execute(status -> {
            var clocks = jdbc.query("SELECT last_seen_at FROM knowledge_quota_control WHERE singleton_id=1 FOR UPDATE",
                    (rs, row) -> rs.getTimestamp("last_seen_at"));
            Timestamp now = jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", Timestamp.class);
            if (clocks.size() != 1 || clocks.get(0) == null || now == null || now.before(clocks.get(0))) {
                throw new IllegalStateException("QUOTA_CLOCK_UNRELIABLE");
            }
            Timestamp retainedBefore = Timestamp.from(now.toInstant().minus(2, ChronoUnit.DAYS));
            // 双条件阻止坏expires_at删除未过期任务；持久操作期限不允许重试时顺延。
            int reservations = jdbc.update("""
                    DELETE FROM knowledge_quota_reservation
                    WHERE expires_at<=? AND deadline<=?
                    ORDER BY expires_at,operation_id,phase,attempt,sequence_no LIMIT 128
                    """, now, retainedBefore);
            // 除expires_at外，再验证窗口本身已经结束且经过两天缓冲。
            int buckets = jdbc.update("""
                    DELETE FROM knowledge_quota_bucket
                    WHERE expires_at<=? AND (
                        (window_kind='MINUTE' AND window_start<=DATE_SUB(?,INTERVAL 1 MINUTE))
                        OR (window_kind='HOUR' AND window_start<=DATE_SUB(?,INTERVAL 1 HOUR))
                        OR (window_kind='DAY' AND window_start<=DATE_SUB(?,INTERVAL 1 DAY)))
                    ORDER BY expires_at,scope_hash,window_kind,window_start LIMIT 128
                    """, now, retainedBefore, retainedBefore, retainedBefore);
            var result = new Cleanup(reservations, buckets);
            Timestamp completed = jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", Timestamp.class);
            if (completed == null || completed.before(now)) { throw new IllegalStateException("QUOTA_CLOCK_UNRELIABLE"); }
            if (jdbc.update("UPDATE knowledge_quota_control SET last_seen_at=? WHERE singleton_id=1", completed) != 1) {
                throw new IllegalStateException("QUOTA_CLEANUP_NOT_COMMITTED");
            }
            return result;
        }));
    }
}
