package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.aifriend.retrieval.application.KnowledgeDeletionRebuildPort.Outcome;
import com.aifriend.retrieval.application.KnowledgeRepositoryPort.Snapshot;
import com.aifriend.retrieval.domain.*;

/** 真实删除/读取/派生发布/回收适配器共用内存SQL状态；不代替真实MySQL锁及方言验收。 */
public class KnowledgeDeletionRebuildSmokeTest {
    private static final UUID OLD=id(99),A=id(1),B=id(2),CA=id(11),CB=id(12);
    private static final EmbeddingProfile PROFILE=new EmbeddingProfile("fixture-space",2);

    @org.junit.jupiter.params.ParameterizedTest(name="frozen deletion {0}")
    @org.junit.jupiter.params.provider.MethodSource("frozenDeletionCases")
    void frozenDeletionRebuildAndUnknownCommitStayRecoverable(FrozenKnowledgeCases.Scenario sample) throws Exception {
        var f=new Fixture(true);f.delete(A);
        switch(sample.operation()) {
            case "DELETE_REBUILD"->{
                assertThat(sample.expected()).isEqualTo("SURVIVING_SOURCE_AVAILABLE");
                assertThat(f.rebuild.rebuild()).isEqualTo(Outcome.REBUILT);
                var current=f.reader.readActive().orElseThrow();
                assertThat(current.documents()).extracting(KnowledgeDocument::id).containsExactly(B);
                var hits=new Bm25KeywordIndex(current,new KnowledgeTokenizer(),1.2,.75,128L*1024*1024)
                        .search(new RetrievalQuery("称呼","zh-CN",1,4),4);
                assertThat(hits).hasSize(1);assertThat(hits.get(0).chunk().documentId()).isEqualTo(B);
                assertThat(f.reader.load(current.version()).keySet()).containsExactly(CB);
            }
            case "DELETE_ALL"->{
                assertThat(sample.expected()).isEqualTo("EMPTY_INDEX_NO_OLD_CONTENT");f.delete(B);
                assertThat(f.rebuild.rebuild()).isEqualTo(Outcome.REBUILT);
                var current=f.reader.readActive().orElseThrow();
                assertThat(current.documents()).isEmpty();assertThat(current.chunks()).isEmpty();
                assertThat(f.reader.load(current.version())).isEmpty();
            }
            case "UNKNOWN_COMMIT"->{
                assertThat(sample.expected()).isEqualTo("NO_DUPLICATE_PUBLICATION");f.loseCommit=true;
                assertThatThrownBy(f.rebuild::rebuild).isInstanceOf(TransactionSystemException.class);
                var published=f.state.active;assertThat(published).isNotEqualTo(OLD);assertThat(f.state.generations).hasSize(2);
                f.writes.clear();assertThat(f.rebuild.rebuild()).isEqualTo(Outcome.NO_WORK);
                assertThat(f.writes).isEmpty();assertThat(f.state.active).isEqualTo(published);assertThat(f.state.generations).hasSize(2);
            }
            default->throw new AssertionError("Unimplemented frozen deletion scenario");
        }
    }
    static java.util.stream.Stream<FrozenKnowledgeCases.Scenario> frozenDeletionCases() throws Exception {
        return FrozenKnowledgeCases.loadIds(Set.of("LC5","LC6","LC8")).stream();
    }
    @Test void deletePublishReadAndRetireSmokeKeepsOnlySurvivingSourceAndRebindsVectors() throws Exception {
        var f=new Fixture(true);var original=f.reader.readActive().orElseThrow();
        f.delete(A);
        assertThatThrownBy(f.reader::readActive).hasMessage("SOURCE_CHANGED");
        assertThat(f.rebuild.rebuild()).isEqualTo(Outcome.REBUILT);
        var next=f.reader.readActive().orElseThrow();
        assertThat(next.documents()).extracting(KnowledgeDocument::id).containsExactly(B);
        assertThat(next.chunks()).extracting(KnowledgeChunk::id).containsExactly(CB);
        assertThat(next.version().generation()).isNotEqualTo(OLD);
        assertThat(next.version().corpusRevision()).isEqualTo(2);
        assertThat(f.reader.load(next.version()).get(CB)).containsExactly(1,2);
        var search=new Bm25KeywordIndex(next,new KnowledgeTokenizer(),1.2,.75,128L*1024*1024);
        assertThat(search.search(new RetrievalQuery("称呼","zh-CN",1,4),20)).isNotEmpty();
        assertThat(search.search(new RetrievalQuery("守护","zh-CN",1,4),20)).isEmpty();
        var rebound=f.state.generations.get(next.version().generation()).vectors.get(CB);
        assertThat(rebound.digest()).isNotEqualTo(f.state.generations.get(OLD).vectors.get(CB).digest());
        assertThat(f.reader.isCurrent(original.version(),original.chunks())).isFalse();
        assertThat(f.rebuild.rebuild()).isEqualTo(Outcome.NO_WORK);
        assertThat(f.state.generations).hasSize(2);
        var cleanup=f.maintenance.sweep();
        assertThat(cleanup.chunks()).isEqualTo(1);assertThat(cleanup.versions()).isEqualTo(1);
        assertThat(f.state.documents.get(A).purged).isTrue();
        assertThat(f.state.chunks).containsOnlyKeys(CB);
        assertThat(f.state.generations).containsOnlyKeys(next.version().generation());
        assertThat(f.reader.readActive()).contains(next);
        assertThat(f.state.documents).containsKeys(A,B); // 墓碑身份保留，不增加用户导入。
        assertThat(f.writes).noneMatch(sql->sql.contains("INSERT INTO knowledge_import_job")||sql.contains("DELETE FROM knowledge_import_job"));
    }
    @Test void deletingAllDocumentsPublishesValidEmptyIndexAndDoesNotCallAnyModel() throws Exception {
        var f=new Fixture(false);f.delete(A);f.delete(B);
        assertThat(f.rebuild.rebuild()).isEqualTo(Outcome.REBUILT);
        var empty=f.reader.readActive().orElseThrow();
        assertThat(empty.documents()).isEmpty();assertThat(empty.chunks()).isEmpty();
        assertThat(empty.version().embeddingProfile()).isEmpty();
        assertThat(f.maintenance.sweep().versions()).isEqualTo(2);
        assertThat(f.reader.readActive()).contains(empty);
    }
    @Test void unchangedIndexAndBusyLeaseDoNotStartWrites() throws Exception {
        var f=new Fixture(true);
        assertThat(f.rebuild.rebuild()).isEqualTo(Outcome.NO_WORK);assertThat(f.writes).isEmpty();
        f.delete(A);f.state.busy=true;
        assertThat(f.rebuild.rebuild()).isEqualTo(Outcome.DEFERRED);assertThat(f.writes).isEmpty();
    }
    @Test void aSecondDeletionRebuildsFromDerivedGenerationAndReclaimsBothAncestors() throws Exception {
        var f=new Fixture(true);f.delete(A);
        assertThat(f.rebuild.rebuild()).isEqualTo(Outcome.REBUILT);
        var first=f.state.active;f.delete(B);
        assertThat(f.rebuild.rebuild()).isEqualTo(Outcome.REBUILT);
        var second=f.state.active;assertThat(second).isNotIn(OLD,first);
        var empty=f.reader.readActive().orElseThrow();assertThat(empty.chunks()).isEmpty();
        assertThat(f.reader.load(empty.version())).isEmpty();
        f.maintenance.sweep();f.maintenance.sweep();
        assertThat(f.state.generations).containsOnlyKeys(second);
        assertThat(f.state.chunks).isEmpty();
        assertThat(f.state.documents.values()).allMatch(d->d.purged);
        assertThat(f.rebuild.rebuild()).isEqualTo(Outcome.NO_WORK);
    }
    @Test void fullGenerationCapacityDefersWithoutChangingActivePointer() throws Exception {
        var f=new Fixture(false);f.delete(A);
        f.state.generations.put(id(97),f.state.generations.get(OLD).copy());
        f.state.generations.put(id(98),f.state.generations.get(OLD).copy());
        assertThat(f.rebuild.rebuild()).isEqualTo(Outcome.DEFERRED);
        assertThat(f.state.active).isEqualTo(OLD);assertThat(f.writes).isEmpty();
    }
    @Test void controlChangeBetweenCaptureAndPublishDefersWithoutNewGeneration() throws Exception {
        var f=new Fixture(true);f.delete(A);f.changeAtLock=2;
        assertThat(f.rebuild.rebuild()).isEqualTo(Outcome.DEFERRED);
        assertThat(f.writes).isEmpty();assertThat(f.state.active).isEqualTo(OLD);
        assertThat(f.state.generations).containsOnlyKeys(OLD);
    }
    @Test void everyPublicationWriteFailureRollsBackPointerAndNewMaterial() throws Exception {
        var baseline=new Fixture(true);baseline.delete(A);baseline.rebuild.rebuild();int count=baseline.writes.size();
        assertThat(count).isEqualTo(6);
        for(int i=1;i<=count;i++) {
            var f=new Fixture(true);f.delete(A);f.failAt=i;
            assertThatThrownBy(f.rebuild::rebuild).hasMessage("SIMULATED_WRITE_FAILURE");
            assertThat(f.state.active).isEqualTo(OLD);assertThat(f.state.generations).containsOnlyKeys(OLD);
            assertThat(f.state.generations.get(OLD).status).isEqualTo("ACTIVE");
            assertThat(f.state.generations.get(OLD).manifest).containsExactly(CA,CB);
            assertThat(f.state.documents.get(A).deleted).isTrue();assertThat(f.state.documents.get(A).purged).isFalse();
            verify(f.tx,atLeastOnce()).rollback(any());
        }
    }
    @Test void finalControlCasFailureAndCorruptedWritebackDoNotPublish() throws Exception {
        for(boolean cas:List.of(true,false)) {
            var f=new Fixture(true);f.delete(A);f.casFailure=cas;f.corruptWrittenVector=!cas;
            assertThatThrownBy(f.rebuild::rebuild).isInstanceOf(RuntimeException.class);
            assertThat(f.state.active).isEqualTo(OLD);assertThat(f.state.generations).containsOnlyKeys(OLD);
        }
    }
    @Test void corruptedHistoricalCorpusOrVectorsNeverRebuildsAnIndex() throws Exception {
        for(boolean corpus:List.of(true,false)) {
            var f=new Fixture(true);f.delete(A);
            if(corpus)f.state.generations.get(OLD).digest=new byte[32];else f.state.generations.get(OLD).vectors.remove(CB);
            assertThatThrownBy(f.rebuild::rebuild).hasMessage("INDEX_INVALID");assertThat(f.writes).isEmpty();
        }
    }
    @Test void unknownCommitIsNotRetriedAndNextScanObservesPublishedPointer() throws Exception {
        var f=new Fixture(true);f.delete(A);f.loseCommit=true;
        assertThatThrownBy(f.rebuild::rebuild).isInstanceOf(TransactionSystemException.class);
        assertThat(f.state.active).isNotEqualTo(OLD);assertThat(f.state.generations).hasSize(2);
        f.writes.clear();assertThat(f.rebuild.rebuild()).isEqualTo(Outcome.NO_WORK);assertThat(f.writes).isEmpty();
    }
    @Test void interruptedScanDoesNotTouchDatabase() throws Exception {
        var f=new Fixture(false);clearInvocations(f.jdbc,f.tx);Thread.currentThread().interrupt();
        try { assertThatThrownBy(f.rebuild::rebuild).isInstanceOf(java.util.concurrent.CancellationException.class); }
        finally { Thread.interrupted(); }
        verifyNoInteractions(f.jdbc,f.tx);
    }
    @Test void migrationSeparatesDeletionProvenanceFromUserImportIdentity() throws Exception {
        String sql=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/db/migration/V35__public_knowledge_index.sql"));
        assertThat(sql).contains("origin='DELETION' AND build_job_id IS NULL AND build_lease_token IS NULL",
                "origin='IMPORT' AND build_job_id IS NOT NULL AND build_lease_token IS NOT NULL",
                "source_generation_id<>id","fk_knowledge_generation_job");
        assertThat(sql).doesNotContain("FOREIGN KEY (source_generation_id)");
    }

    private static UUID id(int n) { return new UUID(0,n); }
    private static byte[] hash(String text) throws Exception { return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); }
    private static final class Doc {
        final KnowledgeDocument value;long revision=1;boolean deleted,purged;
        Doc(KnowledgeDocument value){this.value=value;}
        Doc copy(){var d=new Doc(value);d.revision=revision;d.deleted=deleted;d.purged=purged;return d;}
    }
    private static final class Gen {
        IndexVersion version;UUID jobDocument;byte[] digest;int documents,chunks;String status="ACTIVE";
        Set<UUID> manifest=new LinkedHashSet<>();Map<UUID,KnowledgeVectorCodec.Stored> vectors=new LinkedHashMap<>();
        Gen copy(){var g=new Gen();g.version=version;g.jobDocument=jobDocument;g.digest=digest.clone();g.documents=documents;
            g.chunks=chunks;g.status=status;g.manifest.addAll(manifest);g.vectors.putAll(vectors);return g;}
    }
    private static final class State {
        long version=1,corpus=1;UUID active=OLD;boolean busy;
        Map<UUID,Doc> documents=new LinkedHashMap<>();Map<UUID,KnowledgeChunk> chunks=new LinkedHashMap<>();Map<UUID,Gen> generations=new LinkedHashMap<>();
        State copy(){var s=new State();s.version=version;s.corpus=corpus;s.active=active;s.busy=busy;
            documents.forEach((k,v)->s.documents.put(k,v.copy()));s.chunks.putAll(chunks);generations.forEach((k,v)->s.generations.put(k,v.copy()));return s;}
    }
    /** 供HTTP整合冒烟复用的测试存储；只暴露实际端口，不暴露可写内部状态。 */
    public static final class Fixture {
        final JdbcTemplate jdbc=mock(JdbcTemplate.class);final PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
        final JdbcKnowledgeAdapter reader=new JdbcKnowledgeAdapter(jdbc,tx);
        final JdbcKnowledgeDeletionRebuildAdapter rebuild=new JdbcKnowledgeDeletionRebuildAdapter(jdbc,tx,reader);
        final JdbcKnowledgeDocumentManagementAdapter management=new JdbcKnowledgeDocumentManagementAdapter(jdbc,tx);
        final JdbcKnowledgeHistoryMaintenanceAdapter maintenance=new JdbcKnowledgeHistoryMaintenanceAdapter(jdbc,tx);
        final List<String> writes=new ArrayList<>();final Deque<State> snapshots=new ArrayDeque<>();
        State state=new State();int failAt,locks,changeAtLock;boolean casFailure,corruptWrittenVector,loseCommit;
        public com.aifriend.retrieval.application.KnowledgeRepositoryPort repository() { return reader; }
        public com.aifriend.retrieval.application.KnowledgeDocumentManagementPort documents() { return management; }
        public Outcome rebuildAndSweep() { var result=rebuild.rebuild();maintenance.sweep();return result; }
        public UUID primaryDocument() { return A; }
        public Fixture(boolean vector) throws Exception { this(vector,"守护说明","称呼说明"); }
        @SuppressWarnings({"unchecked","rawtypes"}) public Fixture(boolean vector,String primary,String secondary) throws Exception {
            for(int n=1;n<=2;n++) {
                var d=new KnowledgeDocument(id(n),"guide-"+n,1,"说明"+n,"zh-CN",1,2,n==1?primary:secondary);
                state.documents.put(d.id(),new Doc(d));state.chunks.put(id(n+10),new KnowledgeChunk(id(n+10),d.id(),1,0,"",d.text(),0,d.text().codePointCount(0,d.text().length()),"c1"));
            }
            var g=new Gen();g.version=new IndexVersion(OLD,1,vector?Optional.of(PROFILE):Optional.empty(),KnowledgeTokenizer.VERSION,"c1");
            g.jobDocument=A;g.documents=2;g.chunks=2;g.manifest.addAll(state.chunks.keySet());
            g.digest=new KnowledgeSnapshotDigest().calculate(new Snapshot(g.version,state.documents.values().stream().map(d->d.value).toList(),List.copyOf(state.chunks.values())));
            if(vector)for(UUID chunk:g.manifest)g.vectors.put(chunk,new KnowledgeVectorCodec().encode(PROFILE,OLD,chunk,new float[]{1,2}));
            state.generations.put(OLD,g);
            when(tx.getTransaction(any())).thenAnswer(c->{TransactionDefinition def=c.getArgument(0);
                assertThat(def.getTimeout()).isEqualTo(2);snapshots.push(state.copy());return new SimpleTransactionStatus();});
            doAnswer(c->{state=snapshots.pop();return null;}).when(tx).rollback(any());
            doAnswer(c->{snapshots.pop();if(loseCommit&&!state.active.equals(OLD)){loseCommit=false;throw new TransactionSystemException("SIMULATED_COMMIT_UNKNOWN");}return null;}).when(tx).commit(any());
            Answer<List<?>> query=c->{String sql=c.getArgument(0);RowMapper mapper=c.getArgument(1);
                Object[] args=Arrays.copyOfRange(c.getArguments(),2,c.getArguments().length);
                var rows=select(sql,args);var result=new ArrayList<>();for(var values:rows)result.add(mapper.mapRow(row(values),result.size()));return result;};
            doAnswer(query).when(jdbc).query(anyString(),any(RowMapper.class));
            doAnswer(query).when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
            when(jdbc.queryForObject(anyString(),eq(Integer.class))).thenAnswer(c->{
                assertThat((String)c.getArgument(0)).contains("v.status<>'PURGED'","c.active_generation_id=g.id");
                return state.documents.values().stream().anyMatch(d->d.deleted&&!d.purged)
                    ||state.generations.keySet().stream().anyMatch(id->!id.equals(state.active))?1:0;});
            when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenAnswer(c->{String sql=c.getArgument(0);
                if(sql.contains("FROM knowledge_import_job"))return 0;
                if(sql.contains("FROM knowledge_generation_chunk"))return state.generations.get(UUID.fromString(c.getArgument(2))).manifest.size();
                throw new AssertionError(sql);});
            doAnswer(c->update(c.getArgument(0),Arrays.copyOfRange(c.getArguments(),1,c.getArguments().length)))
                    .when(jdbc).update(anyString(),any(Object[].class));
            doAnswer(c->{String sql=c.getArgument(0);List<Object[]> rows=c.getArgument(1);write(sql);int[] counts=new int[rows.size()];
                for(int i=0;i<rows.size();i++){var r=rows.get(i);var gen=state.generations.get(UUID.fromString((String)r[0]));UUID chunk=UUID.fromString((String)r[1]);
                    if(sql.contains("knowledge_generation_chunk"))gen.manifest.add(chunk);
                    else {assertThat(gen.manifest).contains(chunk);byte[] digest=(byte[])r[4];if(corruptWrittenVector)digest=new byte[32];
                        gen.vectors.put(chunk,new KnowledgeVectorCodec.Stored((byte[])r[3],digest));}counts[i]=1;}return counts;})
                    .when(jdbc).batchUpdate(anyString(),anyList());
        }
        void delete(UUID id){management.invalidate(id,state.documents.get(id).revision);writes.clear();locks=0;}
        private List<Map<String,Object>> select(String sql,Object[] args) throws Exception {
            if(sql.contains("FROM knowledge_index_control WHERE")) {
                assertThat(sql).contains("FOR UPDATE");locks++;if(locks==changeAtLock){state.version++;state.corpus++;}
                return List.of(map("version",state.version,"corpus_revision",state.corpus,"active_id",state.active==null?null:state.active.toString(),"busy",state.busy?1:0));
            }
            if(sql.contains("LEFT JOIN knowledge_index_generation")) {
                if(state.active==null)return List.of(map("active_id",null));var g=state.generations.get(state.active);
                return List.of(map("active_id",state.active.toString(),"generation_id",state.active.toString(),"corpus_revision",g.version.corpusRevision(),
                        "profile_id",g.version.embeddingProfile().map(EmbeddingProfile::id).orElse(null),"dimension",g.version.embeddingProfile().map(EmbeddingProfile::dimension).orElse(null),
                        "tokenizer_version",g.version.tokenizerVersion(),"chunker_version",g.version.chunkerVersion(),"corpus_digest",g.digest,"document_count",g.documents,"chunk_count",g.chunks,"status",g.status));
            }
            if(sql.startsWith("SELECT row_version")) {var d=state.documents.get(UUID.fromString((String)args[0]));return d==null?List.of():List.of(doc(d));}
            if(sql.startsWith("SELECT BIN_TO_UUID(id) id FROM knowledge_index_generation"))return state.generations.keySet().stream().limit(4).map(i->map("id",i.toString())).toList();
            if(sql.startsWith("SELECT BIN_TO_UUID(g.id)"))return state.generations.entrySet().stream().filter(e->!e.getKey().equals(state.active)&&!e.getValue().status.equals("ACTIVE"))
                    .limit(1).map(e->map("id",e.getKey().toString())).toList();
            if(sql.contains("FROM knowledge_import_job WHERE"))return List.of();
            if(sql.startsWith("SELECT BIN_TO_UUID(k.id) id FROM knowledge_chunk"))return state.chunks.values().stream().filter(k->state.generations.values().stream().noneMatch(g->g.manifest.contains(k.id())))
                    .limit(64).map(k->map("id",k.id().toString())).toList();
            if(sql.startsWith("SELECT BIN_TO_UUID(v.document_id)"))return state.documents.values().stream().filter(d->d.deleted&&!d.purged
                    &&state.chunks.values().stream().noneMatch(k->k.documentId().equals(d.value.id()))&&state.generations.values().stream().noneMatch(g->d.value.id().equals(g.jobDocument)))
                    .limit(16).map(d->map("id",d.value.id().toString(),"version",1L)).toList();
            var gen=state.generations.get(UUID.fromString((String)args[0]));
            if(sql.startsWith("SELECT BIN_TO_UUID(chunk_id) id"))return gen.manifest.stream().limit(64).map(i->map("id",i.toString())).toList();
            if(sql.startsWith("SELECT DISTINCT BIN_TO_UUID(d.id)")) {
                var ids=new LinkedHashSet<UUID>();gen.manifest.forEach(i->ids.add(state.chunks.get(i).documentId()));var rows=new ArrayList<Map<String,Object>>();
                for(UUID i:ids)rows.add(doc(state.documents.get(i)));return rows;
            }
            if(sql.startsWith("SELECT BIN_TO_UUID(k.id) chunk_id")) {var rows=new ArrayList<Map<String,Object>>();for(UUID i:gen.manifest){var k=state.chunks.get(i);
                rows.add(map("chunk_id",i.toString(),"document_id",k.documentId().toString(),"document_version",1L,"ordinal",0,"heading","","chunk_text",k.text(),
                        "source_start",k.sourceStart(),"source_end",k.sourceEnd(),"content_digest",hash(k.text()),"chunker_version","c1"));}return rows;}
            if(sql.startsWith("SELECT BIN_TO_UUID(m.chunk_id)"))return gen.manifest.stream().map(i->{var v=gen.vectors.get(i);return map("chunk_id",i.toString(),
                    "dimension",v==null?null:2,"vector_blob",v==null?null:v.bytes(),"digest",v==null?null:v.digest());}).toList();
            throw new AssertionError(sql);
        }
        private int update(String sql,Object[] a){write(sql);
            if(sql.startsWith("UPDATE knowledge_document SET")){var d=state.documents.get(UUID.fromString((String)a[0]));d.deleted=true;d.revision++;return 1;}
            if(sql.startsWith("UPDATE knowledge_index_control SET version")){state.version++;state.corpus++;return 1;}
            if(sql.startsWith("INSERT INTO knowledge_index_generation")){
                assertThat(sql).contains("NULL,NULL,'DELETION'");UUID id=UUID.fromString((String)a[0]);var g=new Gen();
                g.version=new IndexVersion(id,((Number)a[2]).longValue(),a[3]==null?Optional.empty():Optional.of(new EmbeddingProfile((String)a[3],(Integer)a[4])),(String)a[5],(String)a[6]);
                g.digest=((byte[])a[7]).clone();g.documents=(Integer)a[8];g.chunks=(Integer)a[9];g.status="READY";state.generations.put(id,g);return 1;
            }
            if(sql.startsWith("UPDATE knowledge_index_generation")){var g=state.generations.get(UUID.fromString((String)a[0]));g.status=sql.contains("SET status='RETIRED'")?"RETIRED":"ACTIVE";return 1;}
            if(sql.startsWith("UPDATE knowledge_index_control SET active")){assertThat(sql).contains("version=?","corpus_revision=?","lease_until<=UTC_TIMESTAMP(3)");
                if(casFailure)return 0;state.active=UUID.fromString((String)a[0]);state.version++;return 1;}
            if(sql.startsWith("DELETE FROM knowledge_embedding"))return state.generations.get(UUID.fromString((String)a[0])).vectors.remove(UUID.fromString((String)a[1]))==null?0:1;
            if(sql.startsWith("DELETE FROM knowledge_generation_chunk")){var g=state.generations.get(UUID.fromString((String)a[0]));UUID c=UUID.fromString((String)a[1]);assertThat(g.vectors).doesNotContainKey(c);return g.manifest.remove(c)?1:0;}
            if(sql.startsWith("DELETE g")){UUID id=UUID.fromString((String)a[0]);assertThat(id).isNotEqualTo(state.active);assertThat(state.generations.get(id).manifest).isEmpty();state.generations.remove(id);return 1;}
            if(sql.startsWith("DELETE k")){state.chunks.remove(UUID.fromString((String)a[0]));return 1;}
            if(sql.startsWith("UPDATE knowledge_document_version")){state.documents.get(UUID.fromString((String)a[0])).purged=true;return 1;}
            throw new AssertionError(sql);
        }
        private void write(String sql){writes.add(sql);if(writes.size()==failAt)throw new IllegalStateException("SIMULATED_WRITE_FAILURE");}
        private Map<String,Object> doc(Doc d) throws Exception {var v=d.value;return map("document_id",v.id().toString(),"source_key",v.sourceKey(),"document_status",d.deleted?"DELETED":"ACTIVE",
                "status",d.deleted?"DELETED":"ACTIVE","active_version",d.deleted?null:1L,"row_version",d.revision,"deleted_at",d.deleted?Timestamp.from(Instant.parse("2026-09-11T09:00:00Z")):null,
                "version",1L,"title",d.purged?"":v.title(),"locale","zh-CN","min_app_version",1,"max_app_version",2,"original_text",d.purged?null:v.text(),
                "content_digest",hash(v.text()),"chunker_version","c1","version_status",d.purged?"PURGED":"READY");}
        private static Map<String,Object> map(Object... data){var map=new HashMap<String,Object>();for(int i=0;i<data.length;i+=2)map.put((String)data[i],data[i+1]);return map;}
        private ResultSet row(Map<String,Object> data) throws Exception {var rs=mock(ResultSet.class);
            when(rs.getString(anyString())).thenAnswer(c->data.get(c.getArgument(0)));when(rs.getObject(anyString())).thenAnswer(c->data.get(c.getArgument(0)));
            when(rs.getLong(anyString())).thenAnswer(c->data.get(c.getArgument(0))==null?0L:((Number)data.get(c.getArgument(0))).longValue());
            when(rs.getInt(anyString())).thenAnswer(c->data.get(c.getArgument(0))==null?0:((Number)data.get(c.getArgument(0))).intValue());
            when(rs.getBytes(anyString())).thenAnswer(c->data.get(c.getArgument(0)));when(rs.getTimestamp(anyString())).thenAnswer(c->data.get(c.getArgument(0)));return rs;}
    }
}
