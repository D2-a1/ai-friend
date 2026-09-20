package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 实际JDBC适配器与模拟状态/事务，验证SQL顺序、CAS和回滚；不等价InnoDB并发验收。 */
class JdbcKnowledgeDocumentManagementAdapterTest {
    private static final UUID DOC=new UUID(0,1),OTHER=new UUID(0,2),JOB=new UUID(0,3),OTHER_JOB=new UUID(0,4);
    @Test void invalidationStopsPendingJobAdvancesCorpusAndPreservesOtherDocument() throws Exception {
        var f=new Fixture(); f.adapter.invalidate(DOC,5);
        assertThat(f.adapter.find(DOC).orElseThrow().deleted()).isTrue();
        assertThat(f.adapter.find(DOC).orElseThrow().revision()).isEqualTo(6);
        assertThat(f.docs.get(OTHER).get("status")).isEqualTo("ACTIVE");
        assertThat(f.jobs.get(JOB).get("status")).isEqualTo("FAILED");
        assertThat(f.jobs.get(OTHER_JOB).get("status")).isEqualTo("PROCESSING");
        assertThat(f.version).isEqualTo(8);assertThat(f.corpus).isEqualTo(12);assertThat(f.lease).isNull();
        assertThat(f.sql.get(0)).contains("knowledge_index_control","FOR UPDATE");
        assertThat(f.sql.get(1)).contains("knowledge_document","FOR UPDATE");
        assertThat(f.writes).noneMatch(sql->sql.contains("DELETE ") || sql.contains("active_generation_id="));
    }
    @Test void unrelatedBuildLeaseIsPreservedButControlVersionInvalidatesOldPublication() throws Exception {
        var f=new Fixture();f.lease=OTHER_JOB.toString();f.adapter.invalidate(DOC,5);
        assertThat(f.lease).isEqualTo(OTHER_JOB.toString());assertThat(f.version).isEqualTo(8);
        assertThat(f.writes.get(f.writes.size()-1)).doesNotContain("lease_job_id=NULL");
    }
    @Test void replayAfterSuccessDoesNotAdvanceAnything() throws Exception {
        var f=new Fixture();f.adapter.invalidate(DOC,5);int count=f.writes.size();
        f.adapter.invalidate(DOC,5);f.adapter.invalidate(DOC,6);
        assertThat(f.writes).hasSize(count);assertThat(f.version).isEqualTo(8);
        assertThatThrownBy(()->f.adapter.invalidate(DOC,4)).hasMessage("VERSION_MISMATCH");
    }
    @Test void pendingJobIsStoppedButReadyHistoryIsNotRewritten() throws Exception {
        var f=new Fixture();f.jobs.get(JOB).put("status","PENDING");
        UUID ready=new UUID(0,5);var history=job(DOC);history.put("status","READY");f.jobs.put(ready,history);
        f.adapter.invalidate(DOC,5);
        assertThat(f.jobs.get(JOB).get("status")).isEqualTo("FAILED");
        assertThat(f.jobs.get(ready).get("status")).isEqualTo("READY");assertThat(f.jobs).hasSize(3);
    }
    @Test void deletedDocumentWithUnexpectedLiveJobCannotReplaySuccess() throws Exception {
        var f=new Fixture();f.adapter.invalidate(DOC,5);int writes=f.writes.size();
        f.jobs.get(JOB).put("status","PENDING");
        assertThatThrownBy(()->f.adapter.invalidate(DOC,5)).hasMessage("INVALID_KNOWLEDGE_MANAGEMENT_STORAGE");
        assertThat(f.writes).hasSize(writes);
    }
    @Test void wrongRevisionOrMissingIdNeverWrites() throws Exception {
        var f=new Fixture();assertThatThrownBy(()->f.adapter.invalidate(DOC,4)).hasMessage("VERSION_MISMATCH");
        assertThatThrownBy(()->f.adapter.invalidate(new UUID(0,99),5)).hasMessage("NOT_FOUND");assertThat(f.writes).isEmpty();
    }
    @Test void everyWriteFailureRollsBackDocumentJobsAndControl() throws Exception {
        for(int stage=1;stage<=3;stage++) {
            var f=new Fixture();f.failAt=stage;
            assertThatThrownBy(()->f.adapter.invalidate(DOC,5)).isInstanceOf(DataAccessResourceFailureException.class);
            assertThat(f.docs.get(DOC).get("status")).isEqualTo("ACTIVE");
            assertThat(f.jobs.get(JOB).get("status")).isEqualTo("PROCESSING");
            assertThat(f.version).isEqualTo(7);assertThat(f.corpus).isEqualTo(11);assertThat(f.lease).isEqualTo(JOB.toString());
        }
    }
    @Test void failedCasRollsBackEarlierWrites() throws Exception {
        var f=new Fixture();f.zeroAt=3;
        assertThatThrownBy(()->f.adapter.invalidate(DOC,5)).hasMessage("INVALID_KNOWLEDGE_MANAGEMENT_STORAGE");
        assertThat(f.docs.get(DOC).get("status")).isEqualTo("ACTIVE");assertThat(f.version).isEqualTo(7);
    }
    @Test void commitResponseLossCanBeRecoveredBySameRevision() throws Exception {
        var f=new Fixture();f.loseCommit=true;
        assertThatThrownBy(()->f.adapter.invalidate(DOC,5)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(f.docs.get(DOC).get("status")).isEqualTo("DELETED");int writes=f.writes.size();
        f.loseCommit=false;f.adapter.invalidate(DOC,5);assertThat(f.writes).hasSize(writes);
    }
    @Test void corruptedDeletedMetadataAndOverflowFailClosed() throws Exception {
        var f=new Fixture();f.docs.get(DOC).put("status","DELETED");
        assertThatThrownBy(()->f.adapter.find(DOC)).hasMessage("INVALID_KNOWLEDGE_MANAGEMENT_STORAGE");
        f.docs.get(DOC).put("status","ACTIVE");f.docs.get(DOC).put("row_version",Long.MAX_VALUE);
        assertThatThrownBy(()->f.adapter.invalidate(DOC,Long.MAX_VALUE)).hasMessage("RESOURCE_LIMIT");assertThat(f.writes).isEmpty();
    }
    @Test void excessivePendingJobsRejectBeforeAnyChange() throws Exception {
        var f=new Fixture();for(int i=100;i<201;i++) f.jobs.put(new UUID(0,i),job(DOC));
        assertThatThrownBy(()->f.adapter.invalidate(DOC,5)).hasMessage("INVALID_KNOWLEDGE_MANAGEMENT_STORAGE");assertThat(f.writes).isEmpty();
    }
    @Test void concurrentSameRevisionAcrossInstancesOnlyChangesStateOnce() throws Exception {
        var f=new Fixture();var second=new JdbcKnowledgeDocumentManagementAdapter(f.jdbc,f.tx);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var results=pool.invokeAll(List.of(()->{f.adapter.invalidate(DOC,5);return true;},()->{second.invalidate(DOC,5);return true;}),5,TimeUnit.SECONDS);
            for(var result:results) assertThat(result.get()).isEqualTo(true);
            assertThat(f.version).isEqualTo(8);assertThat(f.writes).hasSize(3);
        } finally {pool.shutdownNow();}
    }
    private static Map<String,Object> doc() {var m=new HashMap<String,Object>();m.put("row_version",5L);m.put("status","ACTIVE");m.put("active_version",2L);return m;}
    private static Map<String,Object> job(UUID doc) {return new HashMap<>(Map.of("document",doc,"version",2L,"status","PROCESSING"));}
    private static Map<UUID,Map<String,Object>> copy(Map<UUID,Map<String,Object>> source) {
        var result=new HashMap<UUID,Map<String,Object>>();source.forEach((id,value)->result.put(id,new HashMap<>(value)));return result;
    }
    private static class Fixture {
        final JdbcTemplate jdbc=mock(JdbcTemplate.class);final PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
        final JdbcKnowledgeDocumentManagementAdapter adapter=new JdbcKnowledgeDocumentManagementAdapter(jdbc,tx);
        final Map<UUID,Map<String,Object>> docs=new HashMap<>(),jobs=new HashMap<>();
        final List<String> sql=new ArrayList<>(),writes=new ArrayList<>();final ReentrantLock lock=new ReentrantLock();
        long version=7,corpus=11,oldVersion,oldCorpus;String lease=JOB.toString(),oldLease;
        Map<UUID,Map<String,Object>> oldDocs,oldJobs;int failAt,zeroAt,writeCount;boolean loseCommit;
        @SuppressWarnings({"unchecked","rawtypes"}) Fixture() throws Exception {
            docs.put(DOC,doc());docs.put(OTHER,doc());jobs.put(JOB,job(DOC));jobs.put(OTHER_JOB,job(OTHER));
            when(tx.getTransaction(any())).thenAnswer(call->{
                lock.lock();TransactionDefinition definition=call.getArgument(0);
                assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                assertThat(definition.getTimeout()).isEqualTo(2);
                oldDocs=copy(docs);oldJobs=copy(jobs);oldVersion=version;oldCorpus=corpus;oldLease=lease;writeCount=0;return new SimpleTransactionStatus();
            });
            doAnswer(call->{lock.unlock();if(loseCommit)throw new DataAccessResourceFailureException("commit unknown");return null;}).when(tx).commit(any());
            doAnswer(call->{docs.clear();docs.putAll(oldDocs);jobs.clear();jobs.putAll(oldJobs);version=oldVersion;corpus=oldCorpus;lease=oldLease;lock.unlock();return null;}).when(tx).rollback(any());
            doAnswer(call->{
                String query=call.getArgument(0);sql.add(query);RowMapper mapper=call.getArgument(1);
                var values=new HashMap<String,Object>();values.put("version",version);values.put("corpus_revision",corpus);values.put("lease_job",lease);
                return List.of(mapper.mapRow(row(values),0));
            }).when(jdbc).query(anyString(),any(RowMapper.class));
            doAnswer(call->{
                String query=call.getArgument(0);sql.add(query);RowMapper mapper=call.getArgument(1);UUID id=UUID.fromString(call.getArgument(2));
                if(query.contains("FROM knowledge_document ")) return docs.containsKey(id)?List.of(mapper.mapRow(row(docs.get(id)),0)):List.of();
                var result=new ArrayList<>();
                for(var entry:jobs.entrySet().stream().filter(e->e.getValue().get("document").equals(id)&&Set.of("PENDING","PROCESSING").contains(e.getValue().get("status"))).limit(101).toList()) {
                    var values=new HashMap<>(entry.getValue());values.put("id",entry.getKey().toString());result.add(mapper.mapRow(row(values),0));
                }
                return result;
            }).when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
            when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenAnswer(call->{
                UUID id=UUID.fromString(call.getArgument(2));return (int)jobs.values().stream().filter(j->j.get("document").equals(id)&&Set.of("PENDING","PROCESSING").contains(j.get("status"))).count();
            });
            doAnswer(call->{
                String query=call.getArgument(0);writes.add(query);writeCount++;
                if(writeCount==failAt)throw new DataAccessResourceFailureException("test write failure");if(writeCount==zeroAt)return 0;
                if(query.startsWith("UPDATE knowledge_document ")) {
                    var record=docs.get(UUID.fromString(call.getArgument(1)));record.put("status","DELETED");record.put("active_version",null);
                    record.put("row_version",(long)record.get("row_version")+1);record.put("deleted_at",Timestamp.from(Instant.parse("2026-09-10T00:00:00Z")));
                } else if(query.startsWith("UPDATE knowledge_import_job ")) {
                    var record=jobs.get(UUID.fromString(call.getArgument(1)));record.put("status","FAILED");record.put("version",(long)record.get("version")+1);
                } else {version++;corpus++;if(query.contains("lease_job_id=NULL"))lease=null;}
                return 1;
            }).when(jdbc).update(anyString(),any(Object[].class));
        }
        private ResultSet row(Map<String,Object> values) throws SQLException {
            ResultSet rs=mock(ResultSet.class);
            when(rs.getString(anyString())).thenAnswer(call->values.get(call.getArgument(0)));
            when(rs.getLong(anyString())).thenAnswer(call->values.getOrDefault(call.getArgument(0),0L));
            when(rs.getTimestamp(anyString())).thenAnswer(call->values.get(call.getArgument(0)));
            when(rs.getObject(anyString())).thenAnswer(call->values.get(call.getArgument(0)));return rs;
        }
    }
}
