package com.aifriend.retrieval.infrastructure;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifriend.retrieval.application.KnowledgeHistoryMaintenancePort;

/**
 * 控制行锁内回收失去所有引用的公开片段及旧原文；不创建新版本，不删幂等记录。
 * 版本墓碑保留版本单调性；源删除、用户撤权及配额账本清理由各自流程负责。
 * @author codex
 * @since 1.0.0
 */
public final class JdbcKnowledgeHistoryMaintenanceAdapter implements KnowledgeHistoryMaintenancePort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    static final String UNUSED_CHUNK = """
            NOT EXISTS (SELECT 1 FROM knowledge_generation_chunk m WHERE m.chunk_id=k.id)
            AND NOT EXISTS (SELECT 1 FROM knowledge_import_job j
                WHERE j.document_id=k.document_id AND j.document_version=k.document_version
                AND j.status IN ('PENDING','PROCESSING'))
            """;
    static final String UNUSED_VERSION = """
            v.status<>'PURGED'
            AND NOT EXISTS (SELECT 1 FROM knowledge_document d
                WHERE d.id=v.document_id AND d.active_version=v.version)
            AND NOT EXISTS (SELECT 1 FROM knowledge_chunk k
                WHERE k.document_id=v.document_id AND k.document_version=v.version)
            AND NOT EXISTS (SELECT 1 FROM knowledge_import_job j
                WHERE j.document_id=v.document_id AND j.document_version=v.version
                AND (j.status IN ('PENDING','PROCESSING')
                    OR EXISTS (SELECT 1 FROM knowledge_index_generation g WHERE g.build_job_id=j.id)))
            """;
    static final String UNUSED_GENERATION = """
            NOT EXISTS (SELECT 1 FROM knowledge_index_control c WHERE c.active_generation_id=g.id)
            AND g.status IN ('RETIRED','FAILED','BUILDING','READY')
            AND NOT EXISTS (SELECT 1 FROM knowledge_import_job j WHERE j.id=g.build_job_id
                AND j.status IN ('PENDING','PROCESSING'))
            """;

    /**
     * 创建独立两秒短事务适配器。
     * @param source 数据源
     * @param transactions 对应事务管理器
     */
    public JdbcKnowledgeHistoryMaintenanceAdapter(DataSource source, PlatformTransactionManager transactions) {
        this(new JdbcTemplate(Objects.requireNonNull(source, "source")), transactions);
        jdbc.setQueryTimeout(2);
    }

    JdbcKnowledgeHistoryMaintenanceAdapter(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transaction = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(2);
    }

    /** {@inheritDoc} */
    @Override public Cleanup sweep() {
        return transaction.execute(status -> {
            var controls = jdbc.query("""
                    SELECT version, CASE WHEN lease_until>UTC_TIMESTAMP(3) THEN 1 ELSE 0 END busy
                    FROM knowledge_index_control WHERE singleton_id=1 FOR UPDATE
                    """, (rs, row) -> {
                        long version = rs.getLong("version");
                        int busy = rs.getInt("busy");
                        if (version < 1 || busy < 0 || busy > 1) {
                            throw new IllegalStateException("INVALID_INDEX_CONTROL");
                        }
                        return busy == 1;
                    });
            if (controls.size() != 1) { throw new IllegalStateException("INVALID_INDEX_CONTROL"); }
            if (controls.get(0)) { return new Cleanup(0, 0, State.DEFERRED); }
            boolean retired = retireGenerationBatch();
            List<UUID> chunks = jdbc.query("SELECT BIN_TO_UUID(k.id) id FROM knowledge_chunk k WHERE "
                    + UNUSED_CHUNK + " ORDER BY k.id LIMIT 64", (rs, row) -> UUID.fromString(rs.getString("id")));
            if (chunks.size() > 64 || chunks.stream().distinct().count() != chunks.size()) {
                throw new IllegalStateException("INVALID_CLEANUP_BATCH");
            }
            for (UUID id : chunks) {
                requireOne(jdbc.update("DELETE k FROM knowledge_chunk k WHERE k.id=UUID_TO_BIN(?) AND "
                        + UNUSED_CHUNK, id.toString()));
            }
            var versions = jdbc.query("SELECT BIN_TO_UUID(v.document_id) id,v.version "
                    + "FROM knowledge_document_version v WHERE " + UNUSED_VERSION
                    + " ORDER BY v.document_id,v.version LIMIT 16", (rs, row) -> {
                        long version = rs.getLong("version");
                        if (version < 1) { throw new IllegalStateException("INVALID_CLEANUP_VERSION"); }
                        return new Version(UUID.fromString(rs.getString("id")), version);
                    });
            if (versions.size() > 16 || versions.stream().distinct().count() != versions.size()) {
                throw new IllegalStateException("INVALID_CLEANUP_BATCH");
            }
            for (Version version : versions) {
                requireOne(jdbc.update("""
                        UPDATE knowledge_document_version v SET v.original_text=NULL,v.title='',v.status='PURGED'
                        WHERE v.document_id=UUID_TO_BIN(?) AND v.version=? AND
                        """ + UNUSED_VERSION, version.id().toString(), version.number()));
            }
            if (retired || !chunks.isEmpty() || !versions.isEmpty()) {
                return new Cleanup(chunks.size(), versions.size(), State.PROGRESSED);
            }
            // 旧版本可能仍被活动索引/在途任务引用；零删除不等于物理清理完成。
            Integer pending = jdbc.queryForObject("""
                    SELECT CASE WHEN EXISTS (
                        SELECT 1 FROM knowledge_document_version v
                        JOIN knowledge_document d ON d.id=v.document_id
                        WHERE v.status<>'PURGED' AND (d.active_version IS NULL OR d.active_version<>v.version))
                        OR EXISTS (SELECT 1 FROM knowledge_index_generation g
                            WHERE NOT EXISTS (SELECT 1 FROM knowledge_index_control c WHERE c.active_generation_id=g.id))
                        THEN 1 ELSE 0 END
                    """, Integer.class);
            if (pending == null || pending < 0 || pending > 1) {
                throw new IllegalStateException("INVALID_CLEANUP_REMAINING");
            }
            return new Cleanup(0, 0, pending == 1 ? State.DEFERRED : State.DRAINED);
        });
    }

    /**
     * 最多一个非活动且无待办使用的世代、64个清单引用；外键顺序为向量→清单→空世代。
     * 必须在控制行锁内执行，不能依赖下一次导入才解除旧引用；不触碰活动指针或任务记录。
     * @return 是否确实处理了一个历史世代，不能据此认为全部历史已排空
     */
    private boolean retireGenerationBatch() {
        var generations=jdbc.query("SELECT BIN_TO_UUID(g.id) id FROM knowledge_index_generation g WHERE "
                +UNUSED_GENERATION+" ORDER BY g.id LIMIT 1",(rs,row)->UUID.fromString(rs.getString("id")));
        if(generations.size()>1) throw new IllegalStateException("INVALID_CLEANUP_BATCH");
        if(generations.isEmpty()) return false;
        UUID generation=generations.get(0);
        var chunks=jdbc.query("SELECT BIN_TO_UUID(chunk_id) id FROM knowledge_generation_chunk WHERE generation_id=UUID_TO_BIN(?) ORDER BY chunk_id LIMIT 64",
                (rs,row)->UUID.fromString(rs.getString("id")),generation.toString());
        if(chunks.size()>64 || chunks.stream().distinct().count()!=chunks.size()) throw new IllegalStateException("INVALID_CLEANUP_BATCH");
        for(UUID chunk:chunks) {
            int vectors=jdbc.update("DELETE FROM knowledge_embedding WHERE generation_id=UUID_TO_BIN(?) AND chunk_id=UUID_TO_BIN(?)",
                    generation.toString(),chunk.toString());
            if(vectors<0 || vectors>1) throw new IllegalStateException("INVALID_CLEANUP_BATCH");
            requireOne(jdbc.update("DELETE FROM knowledge_generation_chunk WHERE generation_id=UUID_TO_BIN(?) AND chunk_id=UUID_TO_BIN(?)",
                    generation.toString(),chunk.toString()));
        }
        Integer remaining=jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_generation_chunk WHERE generation_id=UUID_TO_BIN(?)",Integer.class,generation.toString());
        if(remaining==null || remaining<0 || remaining>2000) throw new IllegalStateException("INVALID_CLEANUP_BATCH");
        if(remaining==0) requireOne(jdbc.update("DELETE g FROM knowledge_index_generation g WHERE g.id=UUID_TO_BIN(?) AND "
                +UNUSED_GENERATION,generation.toString()));
        return true;
    }

    private static void requireOne(int count) {
        if (count != 1) { throw new ConcurrencyFailureException("KNOWLEDGE_CLEANUP_CONFLICT"); }
    }

    private record Version(UUID id, long number) { }
}
