package com.aifriend.retrieval.infrastructure;

import java.util.*;
import java.util.concurrent.CancellationException;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.aifriend.retrieval.application.KnowledgeDeletionRebuildPort;
import com.aifriend.retrieval.application.KnowledgeRepositoryPort.Snapshot;
import com.aifriend.retrieval.domain.IndexVersion;

/**
 * 从完整旧索引派生删除后的不可变世代。两次控制锁短事务之间仅做本地摘要/向量重绑定。
 * 无模型、导入任务或权限扩张；未知提交向上传播，后续扫描只按权威活动指针重新判断。
 * @author Codex
 * @since 1.0.0
 */
public final class JdbcKnowledgeDeletionRebuildAdapter implements KnowledgeDeletionRebuildPort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final JdbcKnowledgeAdapter reader;
    private final KnowledgeSnapshotDigest digests=new KnowledgeSnapshotDigest();
    private final KnowledgeVectorCodec codec=new KnowledgeVectorCodec();

    /**
     * 使用既有数据源、独立有界JdbcTemplate，不在构造时访问数据库。
     * @param source 受控数据源
     * @param transactions 对应事务管理器
     */
    public JdbcKnowledgeDeletionRebuildAdapter(DataSource source,PlatformTransactionManager transactions) {
        this(new JdbcTemplate(Objects.requireNonNull(source)),transactions);
        jdbc.setQueryTimeout(2);jdbc.setFetchSize(128);
    }
    JdbcKnowledgeDeletionRebuildAdapter(JdbcTemplate jdbc,PlatformTransactionManager transactions) {
        this(jdbc,transactions,new JdbcKnowledgeAdapter(jdbc,transactions));
    }
    JdbcKnowledgeDeletionRebuildAdapter(JdbcTemplate jdbc,PlatformTransactionManager transactions,JdbcKnowledgeAdapter reader) {
        this.jdbc=Objects.requireNonNull(jdbc);this.reader=Objects.requireNonNull(reader);
        transaction=new TransactionTemplate(Objects.requireNonNull(transactions));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);transaction.setTimeout(2);
    }

    /** {@inheritDoc} */
    @Override public Outcome rebuild() {
        checkCancelled();
        var captured=transaction.execute(tx->capture());
        if(captured==null) throw invalid();
        if(captured.outcome()!=null) return captured.outcome();
        var old=captured.source();
        var oldVersion=old.snapshot().version();
        var next=new Snapshot(new IndexVersion(UUID.randomUUID(),captured.control().corpus(),
                oldVersion.embeddingProfile(),oldVersion.tokenizerVersion(),oldVersion.chunkerVersion()),
                old.snapshot().documents().stream().filter(d->!old.deleted().contains(d.id())).toList(),
                old.snapshot().chunks().stream().filter(c->!old.deleted().contains(c.documentId())).toList());
        // 编码/摘要在事务外完成，最多2000个4096维向量；保守瞬态预算而非实测峰值。
        long bytes=old.snapshot().documents().stream().mapToLong(d->d.text().length()*8L).sum()
                +old.snapshot().chunks().size()*(1024L+oldVersion.embeddingProfile().map(p->p.dimension()*16L).orElse(0L));
        if(bytes>128L*1024*1024) throw new IllegalStateException("DELETION_REBUILD_RESOURCE_LIMIT");
        var encoded=new LinkedHashMap<UUID,KnowledgeVectorCodec.Stored>();
        for(var chunk:next.chunks()) {
            checkCancelled();
            if(next.version().embeddingProfile().isPresent()) encoded.put(chunk.id(),codec.encode(
                    next.version().embeddingProfile().orElseThrow(),next.version().generation(),chunk.id(),captured.vectors().get(chunk.id())));
        }
        byte[] digest=digests.calculate(next);checkCancelled();
        return transaction.execute(tx->publish(captured,next,encoded,digest));
    }

    private Captured capture() {
        var control=control();
        if(control.busy()) return Captured.outcome(Outcome.DEFERRED);
        if(control.active()==null) return Captured.outcome(Outcome.NO_WORK);
        if(!capacity()) return Captured.outcome(Outcome.DEFERRED);
        var source=reader.deletionSourceInTransaction().orElseThrow(JdbcKnowledgeDeletionRebuildAdapter::invalid);
        if(!control.active().equals(source.snapshot().version().generation())
                || control.corpus()<source.snapshot().version().corpusRevision()) throw invalid();
        if(source.deleted().isEmpty()) return Captured.outcome(Outcome.NO_WORK);
        if(control.corpus()==source.snapshot().version().corpusRevision()) throw invalid();
        Map<UUID,float[]> vectors=source.snapshot().version().embeddingProfile().isPresent()
                ?reader.vectorsInTransaction(source.snapshot()):Map.of();
        checkCancelled();return new Captured(control,source,vectors,null);
    }

    private Outcome publish(Captured captured,Snapshot next,Map<UUID,KnowledgeVectorCodec.Stored> encoded,byte[] digest) {
        checkCancelled();
        var current=control();
        if(!current.equals(captured.control()) || current.busy() || !capacity()) return Outcome.DEFERRED;
        if(current.version()==Long.MAX_VALUE) throw invalid();
        var source=reader.deletionSourceInTransaction().orElseThrow(JdbcKnowledgeDeletionRebuildAdapter::invalid);
        if(!source.equals(captured.source())) return Outcome.DEFERRED;
        var version=next.version();String id=version.generation().toString();
        requireOne(jdbc.update("""
                INSERT INTO knowledge_index_generation
                (id,build_job_id,build_lease_token,origin,source_generation_id,corpus_revision,profile_id,dimension,
                 tokenizer_version,chunker_version,corpus_digest,document_count,chunk_count,status,created_at)
                VALUES (UUID_TO_BIN(?),NULL,NULL,'DELETION',UUID_TO_BIN(?),?,?,?,?,?,?,?,?,'READY',UTC_TIMESTAMP(3))
                """,id,current.active().toString(),version.corpusRevision(),
                version.embeddingProfile().map(p->p.id()).orElse(null),version.embeddingProfile().map(p->p.dimension()).orElse(null),
                version.tokenizerVersion(),version.chunkerVersion(),digest,next.documents().size(),next.chunks().size()));
        for(int start=0;start<next.chunks().size();start+=64) {
            checkCancelled();var batch=next.chunks().subList(start,Math.min(start+64,next.chunks().size()));
            requireBatch(jdbc.batchUpdate("INSERT INTO knowledge_generation_chunk (generation_id,chunk_id) VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?))",
                    batch.stream().map(c->new Object[]{id,c.id().toString()}).toList()),batch.size());
            if(version.embeddingProfile().isPresent()) requireBatch(jdbc.batchUpdate("""
                    INSERT INTO knowledge_embedding (generation_id,chunk_id,dimension,vector_blob,digest)
                    VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?)
                    """,batch.stream().map(c->new Object[]{id,c.id().toString(),version.embeddingProfile().orElseThrow().dimension(),
                            encoded.get(c.id()).bytes(),encoded.get(c.id()).digest()}).toList()),batch.size());
        }
        requireOne(jdbc.update("UPDATE knowledge_index_generation SET status='RETIRED' WHERE id=UUID_TO_BIN(?) AND status='ACTIVE'",current.active().toString()));
        requireOne(jdbc.update("UPDATE knowledge_index_generation SET status='ACTIVE' WHERE id=UUID_TO_BIN(?) AND status='READY'",id));
        requireOne(jdbc.update("""
                UPDATE knowledge_index_control SET active_generation_id=UUID_TO_BIN(?),version=version+1
                WHERE singleton_id=1 AND version=? AND corpus_revision=? AND active_generation_id=UUID_TO_BIN(?)
                    AND (lease_until IS NULL OR lease_until<=UTC_TIMESTAMP(3))
                """,id,current.version(),current.corpus(),current.active().toString()));
        if(!control().equals(new Control(current.version()+1,current.corpus(),version.generation(),false))
                || !reader.activeInTransaction().orElseThrow(JdbcKnowledgeDeletionRebuildAdapter::invalid).equals(next)) throw invalid();
        if(version.embeddingProfile().isPresent()) {
            var saved=reader.vectorsInTransaction(next);
            if(!saved.keySet().equals(encoded.keySet()) || saved.entrySet().stream()
                    .anyMatch(e->!Arrays.equals(e.getValue(),captured.vectors().get(e.getKey())))) throw invalid();
        }
        checkCancelled();return Outcome.REBUILT;
    }

    private Control control() {
        checkCancelled();
        var rows=jdbc.query("""
                SELECT version,corpus_revision,BIN_TO_UUID(active_generation_id) active_id,
                    CASE WHEN lease_until>UTC_TIMESTAMP(3) THEN 1 ELSE 0 END busy
                FROM knowledge_index_control WHERE singleton_id=1 FOR UPDATE
                """,(rs,row)->{
                    long version=rs.getLong("version"),corpus=rs.getLong("corpus_revision");int busy=rs.getInt("busy");
                    if(version<1 || corpus<0 || busy<0 || busy>1) throw invalid();
                    String active=rs.getString("active_id");
                    return new Control(version,corpus,active==null?null:UUID.fromString(active),busy==1);
                });
        if(rows.size()!=1) throw invalid();return rows.get(0);
    }
    private boolean capacity() {
        var ids=jdbc.query("SELECT BIN_TO_UUID(id) id FROM knowledge_index_generation ORDER BY id LIMIT 4",
                (rs,row)->UUID.fromString(rs.getString("id")));
        if(ids.size()>3 || ids.stream().distinct().count()!=ids.size()) throw invalid();
        return ids.size()<3;
    }
    private static void requireBatch(int[] counts,int expected) {
        if(counts==null || counts.length!=expected) throw invalid();
        for(int count:counts) if(count!=1 && count!=java.sql.Statement.SUCCESS_NO_INFO) throw invalid();
    }
    private static void requireOne(int count) { if(count!=1) throw invalid(); }
    private static IllegalStateException invalid() { return new IllegalStateException("INVALID_DELETION_REBUILD_STATE"); }
    private static void checkCancelled() { if(Thread.currentThread().isInterrupted()) throw new CancellationException("DELETION_REBUILD_CANCELLED"); }
    private record Control(long version,long corpus,UUID active,boolean busy) { }
    private record Captured(Control control,JdbcKnowledgeAdapter.DeletionSource source,Map<UUID,float[]> vectors,Outcome outcome) {
        static Captured outcome(Outcome outcome) { return new Captured(null,null,Map.of(),outcome); }
    }
}
