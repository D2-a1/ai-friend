package com.aifriend.retrieval.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.aifriend.retrieval.application.KnowledgeDocumentManagementPort;
import com.aifriend.retrieval.application.KnowledgeImportException;
import com.aifriend.retrieval.application.KnowledgeImportException.Kind;

/**
 * 共用控制锁内逻辑失效文档、在途job和旧发布资格；不删除历史或幂等材料。
 * 仍含被删来源的旧索引由既有Reader判为不可用，后续有效构建才能恢复，不混用旧证据。
 * @author Codex
 * @since 1.0.0
 */
public final class JdbcKnowledgeDocumentManagementAdapter implements KnowledgeDocumentManagementPort {
    private static final String DOCUMENT="SELECT row_version,status,deleted_at,active_version FROM knowledge_document WHERE id=UUID_TO_BIN(?)";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate write,read;
    /**
     * 创建具有独立短事务和查询超时的文档管理适配器。
     * @param source 受控数据源
     * @param transactions 对应事务管理器
     */
    public JdbcKnowledgeDocumentManagementAdapter(DataSource source,PlatformTransactionManager transactions) {
        this(new JdbcTemplate(Objects.requireNonNull(source)),transactions); jdbc.setQueryTimeout(2);
    }
    JdbcKnowledgeDocumentManagementAdapter(JdbcTemplate jdbc,PlatformTransactionManager transactions) {
        this.jdbc=Objects.requireNonNull(jdbc); Objects.requireNonNull(transactions);
        write=new TransactionTemplate(transactions); write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); write.setTimeout(2);
        read=new TransactionTemplate(transactions); read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); read.setReadOnly(true); read.setTimeout(2);
    }
    /** {@inheritDoc} */
    @Override public Optional<DocumentState> find(UUID id) {
        Objects.requireNonNull(id); return read.execute(tx->document(id,false));
    }
    /** {@inheritDoc} */
    @Override public void invalidate(UUID id,long expectedRevision) {
        Objects.requireNonNull(id); if(expectedRevision<1) throw new IllegalArgumentException("INVALID_DOCUMENT_REVISION");
        write.executeWithoutResult(tx->{
            var controls=jdbc.query("SELECT version,corpus_revision,BIN_TO_UUID(lease_job_id) lease_job FROM knowledge_index_control WHERE singleton_id=1 FOR UPDATE",
                    (rs,row)->new Control(rs.getLong("version"),rs.getLong("corpus_revision"),rs.getString("lease_job")));
            if(controls.size()!=1) throw invalid();
            Control control=controls.get(0);
            if(control.version()<1 || control.corpus()<0) throw invalid();
            var prior=document(id,true).orElseThrow(()->new KnowledgeImportException(Kind.NOT_FOUND));
            if(prior.deleted()) {
                if(expectedRevision!=prior.revision() && expectedRevision!=prior.revision()-1) throw new KnowledgeImportException(Kind.VERSION_MISMATCH);
                noPending(id);
                return;
            }
            if(prior.revision()!=expectedRevision) throw new KnowledgeImportException(Kind.VERSION_MISMATCH);
            if(prior.revision()==Long.MAX_VALUE || control.version()==Long.MAX_VALUE || control.corpus()==Long.MAX_VALUE)
                throw new KnowledgeImportException(Kind.RESOURCE_LIMIT);
            var jobs=jdbc.query("SELECT BIN_TO_UUID(id) id,version FROM knowledge_import_job WHERE document_id=UUID_TO_BIN(?) AND status IN ('PENDING','PROCESSING') ORDER BY id LIMIT 101 FOR UPDATE",
                    (rs,row)->new Job(UUID.fromString(rs.getString("id")),rs.getLong("version")),id.toString());
            if(jobs.size()>100 || jobs.stream().map(Job::id).distinct().count()!=jobs.size()) throw invalid();
            for(var job:jobs) if(job.version()<1 || job.version()==Long.MAX_VALUE) throw invalid();
            requireOne(jdbc.update("""
                    UPDATE knowledge_document SET status='DELETED',active_version=NULL,row_version=row_version+1,
                        deleted_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3)
                    WHERE id=UUID_TO_BIN(?) AND row_version=? AND status='ACTIVE' AND deleted_at IS NULL
                    """,id.toString(),expectedRevision));
            for(var job:jobs) requireOne(jdbc.update("""
                    UPDATE knowledge_import_job SET status='FAILED',error_code='SOURCE_CHANGED',version=version+1,
                        lease_token=NULL,lease_until=NULL,updated_at=UTC_TIMESTAMP(3)
                    WHERE id=UUID_TO_BIN(?) AND document_id=UUID_TO_BIN(?) AND version=? AND status IN ('PENDING','PROCESSING')
                    """,job.id().toString(),id.toString(),job.version()));
            boolean release=control.leaseJob()!=null && jobs.stream().anyMatch(job->job.id().toString().equals(control.leaseJob()));
            requireOne(jdbc.update("UPDATE knowledge_index_control SET version=version+1,corpus_revision=corpus_revision+1"
                    +(release?",lease_job_id=NULL,lease_token=NULL,lease_until=NULL":"")+" WHERE singleton_id=1 AND version=? AND corpus_revision=?",
                    control.version(),control.corpus()));
            if(!document(id,false).orElseThrow(JdbcKnowledgeDocumentManagementAdapter::invalid)
                    .equals(new DocumentState(id,expectedRevision+1,true))) throw invalid();
            noPending(id);
        });
    }
    private void noPending(UUID id) {
        Integer pending=jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_import_job WHERE document_id=UUID_TO_BIN(?) AND status IN ('PENDING','PROCESSING')",Integer.class,id.toString());
        if(pending==null || pending!=0) throw invalid();
    }
    private Optional<DocumentState> document(UUID id,boolean lock) {
        var rows=jdbc.query(DOCUMENT+(lock?" FOR UPDATE":""),(rs,row)->map(id,rs),id.toString());
        if(rows.size()>1) throw invalid(); return rows.stream().findFirst();
    }
    private static DocumentState map(UUID id,ResultSet rs) throws SQLException {
        long revision=rs.getLong("row_version"); String state=rs.getString("status");
        boolean deleted="DELETED".equals(state),timestamp=rs.getTimestamp("deleted_at")!=null;
        if(revision<1 || (!deleted && !"ACTIVE".equals(state)) || deleted!=timestamp
                || (deleted && rs.getObject("active_version")!=null)) throw invalid();
        return new DocumentState(id,revision,deleted);
    }
    private static void requireOne(int count) { if(count!=1) throw invalid(); }
    private static IllegalStateException invalid() { return new IllegalStateException("INVALID_KNOWLEDGE_MANAGEMENT_STORAGE"); }
    private record Control(long version,long corpus,String leaseJob) { }
    private record Job(UUID id,long version) { }
}
