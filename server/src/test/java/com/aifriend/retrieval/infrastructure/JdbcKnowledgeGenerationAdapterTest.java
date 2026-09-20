package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.aifriend.retrieval.application.KnowledgeBuildSourcePort.Source;
import com.aifriend.retrieval.application.KnowledgeGenerationPort.Build;
import com.aifriend.retrieval.application.KnowledgeImportLeasePort.Claim;
import com.aifriend.retrieval.application.KnowledgeImportRegistrationPort.BuildSpecification;
import com.aifriend.retrieval.domain.EmbeddingProfile;
import com.aifriend.retrieval.domain.KnowledgeDocument;
import com.aifriend.retrieval.domain.KnowledgeImportJob;
import com.aifriend.retrieval.domain.RetrievalQuery;

/** 有回滚的SQL操作模拟，不连接MySQL，不能作为真实迁移或隔离级别验收。 */
class JdbcKnowledgeGenerationAdapterTest {
    private static UUID id(int n) { return new UUID(0, n); }

    @Test void sm04UpdatePublicationChangesAnswerVersionAndInvalidatesOldEvidence() throws Exception {
        assertUpdatePublication();
    }

    @org.junit.jupiter.params.ParameterizedTest(name="frozen publication {0}")
    @org.junit.jupiter.params.provider.MethodSource("frozenPublicationCases")
    void frozenPublicationChangesAllOrNothing(FrozenKnowledgeCases.Scenario sample) throws Exception {
        switch(sample.operation()) {
            case "UPDATE_PUBLISH"->{
                assertThat(sample.expected()).isEqualTo("NEW_VERSION_ONLY");assertUpdatePublication();
            }
            case "PUBLISH_ROLLBACK"->{
                assertThat(sample.expected()).isEqualTo("OLD_POINTER_NO_PARTIAL_PUBLICATION");
                var db=new Simulation(false);db.seedReadableOldIndex();db.fill();var before=db.checkpoint();
                db.rejectCas="control";
                assertThatThrownBy(()->db.adapter.publish(db.build)).isInstanceOf(ConcurrencyFailureException.class);
                assertThat(db.checkpoint()).isEqualTo(before);assertThat(db.active).isEqualTo(id(88).toString());
                assertThat(db.jobReady).isFalse();
                assertThat(new JdbcKnowledgeAdapter(db.jdbc,db.transactions).readActive().orElseThrow().documents())
                        .anyMatch(d->d.id().equals(id(10))&&d.version()==1&&d.text().equals("旧的守护说明"));
            }
            default->throw new AssertionError("Unimplemented frozen publication scenario");
        }
    }
    static java.util.stream.Stream<FrozenKnowledgeCases.Scenario> frozenPublicationCases() throws Exception {
        return FrozenKnowledgeCases.loadIds(java.util.Set.of("LC3","LC7")).stream();
    }

    private void assertUpdatePublication() throws Exception {
        var db=new Simulation(false);db.seedReadableOldIndex();
        var reader=new JdbcKnowledgeAdapter(db.jdbc,db.transactions);
        var old=reader.readActive().orElseThrow();
        var oldHits=new Bm25KeywordIndex(old,new KnowledgeTokenizer(),1.2,.75,128L*1024*1024)
                .search(new RetrievalQuery("守护","zh-CN",1,4),20);
        assertThat(oldHits).anyMatch(h->h.chunk().documentVersion()==1 && h.chunk().text().equals("旧的守护说明"));
        db.fill();assertThat(reader.readActive()).contains(old);
        db.adapter.publish(db.build);
        var current=reader.readActive().orElseThrow();
        var hits=new Bm25KeywordIndex(current,new KnowledgeTokenizer(),1.2,.75,128L*1024*1024)
                .search(new RetrievalQuery("守护","zh-CN",1,4),20);
        assertThat(hits).anyMatch(h->h.chunk().documentVersion()==2 && h.chunk().text().equals("新的守护说明"));
        assertThat(hits).noneMatch(h->h.chunk().text().equals("旧的守护说明"));
        assertThat(reader.isCurrent(old.version(),old.chunks())).isFalse();
        assertThat(current.documents()).anyMatch(d->d.id().equals(id(11)) && d.version()==1);
    }

    @Test void completeLexicalGenerationReachesProductionReaderAndSearch() throws Exception {
        var db = new Simulation(false);
        db.fill();
        assertThat(db.active).isEqualTo(id(88).toString());
        assertThat(db.jobReady).isFalse();
        db.adapter.publish(db.build);
        assertThat(db.active).isEqualTo(id(99).toString());
        assertThat(db.jobReady).isTrue();
        var reader = new JdbcKnowledgeAdapter(db.jdbc, db.transactions);
        var snapshot = reader.readActive().orElseThrow();
        assertThat(snapshot).isEqualTo(db.build.snapshot());
        var search = new Bm25KeywordIndex(snapshot, new KnowledgeTokenizer(), 1.2, .75, 128L * 1024 * 1024);
        assertThat(search.search(new RetrievalQuery("消息", "zh-CN", 1, 4), 20))
                .anyMatch(hit -> hit.chunk().documentId().equals(id(11)));
        verify(db.sources, times(3)).loadLocked(db.build.source().claim());
        verify(db.sources, never()).load(any());
    }

    @Test void fullVectorGenerationCanBeReadWithSameProfileAndValues() throws Exception {
        var db = new Simulation(true);
        db.fill();
        db.adapter.publish(db.build);
        var reader = new JdbcKnowledgeAdapter(db.jdbc, db.transactions);
        var values = reader.load(db.build.snapshot().version());
        assertThat(values.keySet()).isEqualTo(new HashSet<>(db.ids()));
        values.values().forEach(vector -> assertThat(vector).containsExactly(1f, 2f));
    }

    @Test void beginAndBatchReplayDoNotOverwriteOrDuplicate() throws Exception {
        var db = new Simulation(true);
        db.fill();
        var before = db.checkpoint();
        db.adapter.begin(db.build);
        db.adapter.stage(db.build, db.ids(), db.vectors());
        assertThat(db.checkpoint()).isEqualTo(before);
        assertThat(db.generations).hasSize(2);
        assertThat(db.manifest.get(id(99).toString())).hasSize(2);
    }

    @Test void missingChunkPreventsAnyPublication() throws Exception {
        var db = new Simulation(false);
        db.adapter.begin(db.build);
        db.adapter.stage(db.build, List.of(db.ids().get(0)), Map.of());
        var before = db.checkpoint();
        assertThatThrownBy(() -> db.adapter.publish(db.build)).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.checkpoint()).isEqualTo(before);
        assertThat(db.active).isEqualTo(id(88).toString());
    }

    @Test void missingVectorAndWrongSpaceNeverProducePartialSuccess() throws Exception {
        var db = new Simulation(true);
        db.fill();
        var key = id(99) + ":" + db.ids().get(0);
        var original = db.embeddings.remove(key);
        assertThatThrownBy(() -> db.adapter.publish(db.build)).isInstanceOf(IllegalArgumentException.class);
        db.embeddings.put(key, original);
        original.put("digest", new byte[32]);
        assertThatThrownBy(() -> db.adapter.publish(db.build)).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.active).isEqualTo(id(88).toString());
    }

    @Test void corruptChunkAndAlteredCompleteManifestFailClosed() throws Exception {
        var db = new Simulation(false);
        db.fill();
        var chunk = db.chunks.get(db.ids().get(0).toString());
        chunk.put("content_digest", new byte[32]);
        assertThatThrownBy(() -> db.adapter.publish(db.build)).hasMessage("CORRUPT_CHUNK");
        chunk = db.chunks.get(db.ids().get(0).toString());
        chunk.put("chunk_text", "另一段文字");
        chunk.put("content_digest", sha("另一段文字"));
        assertThatThrownBy(() -> db.adapter.publish(db.build)).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.active).isEqualTo(id(88).toString());
    }

    @Test void repeatedBatchWithDifferentVectorRollsBackInsteadOfOverwriting() throws Exception {
        var db = new Simulation(true);
        db.fill();
        var before = db.checkpoint();
        var changed = db.vectors();
        changed.put(db.ids().get(0), new float[] {9, 8});
        assertThatThrownBy(() -> db.adapter.stage(db.build, db.ids(), changed)).hasMessage("STAGED_VECTOR_CONFLICT");
        assertThat(db.checkpoint()).isEqualTo(before);
    }

    @Test void staleClaimBlocksEveryMutationEntry() throws Exception {
        var db = new Simulation(false);
        db.fill();
        var before = db.checkpoint();
        when(db.sources.loadLocked(any())).thenThrow(new ConcurrencyFailureException("BUILD_SOURCE_CHANGED"));
        assertThatThrownBy(() -> db.adapter.begin(db.build)).hasMessage("BUILD_SOURCE_CHANGED");
        assertThatThrownBy(() -> db.adapter.stage(db.build, db.ids(), Map.of())).hasMessage("BUILD_SOURCE_CHANGED");
        assertThatThrownBy(() -> db.adapter.publish(db.build)).hasMessage("BUILD_SOURCE_CHANGED");
        assertThat(db.checkpoint()).isEqualTo(before);
    }

    @Test void sourceChangedAfterStageCannotPublishOldContent() throws Exception {
        var db = new Simulation(false);
        db.fill();
        var old = db.build.source();
        var changed = new ArrayList<>(old.documents());
        changed.set(1, new KnowledgeDocument(id(11), "public-11", 1, "说明", "zh-CN", 1, 10, "修改后的其他来源"));
        when(db.sources.loadLocked(any())).thenReturn(new Source(old.claim(), old.specification(), old.targetDocument(), old.targetVersion(), changed));
        assertThatThrownBy(() -> db.adapter.publish(db.build)).hasMessage("BUILD_SOURCE_CHANGED");
        assertThat(db.active).isEqualTo(id(88).toString());
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7})
    void everyPublicationWriteFailureRollsBackPointerSourcesAndJob(int step) throws Exception {
        var db = new Simulation(false);
        db.fill();
        var before = db.checkpoint();
        db.failAtWrite = step;
        assertThatThrownBy(() -> db.adapter.publish(db.build)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(db.checkpoint()).isEqualTo(before);
        assertThat(db.active).isEqualTo(id(88).toString());
        assertThat(db.jobReady).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"control", "job"})
    void expiryAtFinalCasRollsBackEvenAfterOtherUpdates(String reject) throws Exception {
        var db = new Simulation(false);
        db.fill();
        var before = db.checkpoint();
        db.rejectCas = reject;
        assertThatThrownBy(() -> db.adapter.publish(db.build)).isInstanceOf(ConcurrencyFailureException.class);
        assertThat(db.checkpoint()).isEqualTo(before);
    }

    @Test void expiredLeaseAfterBatchRollsBackBatchButRetainsPreviousGeneration() throws Exception {
        var db = new Simulation(false);
        db.adapter.begin(db.build);
        var before = db.checkpoint();
        when(db.jdbc.queryForObject(eq("SELECT UTC_TIMESTAMP(3)"), eq(Timestamp.class)))
                .thenReturn(Timestamp.from(db.now.plusSeconds(30)));
        assertThatThrownBy(() -> db.adapter.stage(db.build, db.ids(), Map.of())).hasMessage("IMPORT_LEASE_LOST");
        assertThat(db.checkpoint()).isEqualTo(before);
    }

    @Test void anotherAttemptCannotTakeOverExistingGeneration() throws Exception {
        var db = new Simulation(false);
        db.fill();
        db.generations.get(id(99).toString()).put("build_lease_token", id(777).toString());
        assertThatThrownBy(() -> db.adapter.publish(db.build)).hasMessage("GENERATION_CONFLICT");
        assertThat(db.active).isEqualTo(id(88).toString());
    }

    @Test void invalidBatchIsRejectedBeforeStartingTransaction() throws Exception {
        var db = new Simulation(true);
        clearInvocations(db.transactions);
        assertThatThrownBy(() -> db.adapter.stage(db.build, List.of(), Map.of())).hasMessage("INVALID_STAGE_BATCH");
        assertThatThrownBy(() -> db.adapter.stage(db.build, List.of(db.ids().get(0), db.ids().get(0)), Map.of())).hasMessage("INVALID_STAGE_BATCH");
        assertThatThrownBy(() -> db.adapter.stage(db.build, List.of(id(600)), Map.of())).hasMessage("FOREIGN_STAGE_CHUNK");
        assertThatThrownBy(() -> db.adapter.stage(db.build, db.ids(), Map.of())).hasMessage("INVALID_STAGE_VECTORS");
        var invalid = db.vectors();
        invalid.put(db.ids().get(0), new float[] {Float.NaN, 1});
        assertThatThrownBy(() -> db.adapter.stage(db.build, db.ids(), invalid)).hasMessage("INVALID_VECTOR");
        verify(db.transactions, never()).getTransaction(any());
    }

    @Test void cleanupRemovesOnlyNonActiveGenerationAndNeverItsDocumentData() throws Exception {
        var db = new Simulation(false);
        db.generations.put(id(77).toString(), new HashMap<>(Map.of("status", "RETIRED")));
        db.manifest.put(id(77).toString(), new ArrayList<>());
        db.adapter.begin(db.build);
        assertThat(db.generations).containsKeys(id(88).toString(), id(99).toString()).doesNotContainKey(id(77).toString());
        assertThat(db.sql).noneMatch(s -> s.startsWith("DELETE FROM knowledge_document") || s.startsWith("DELETE FROM knowledge_chunk"));
        assertThat(db.active).isEqualTo(id(88).toString());
    }

    @Test void commitResponseLossIsNotBlindlyReplayedAndReadyStateRemains() throws Exception {
        var db = new Simulation(false);
        db.fill();
        db.loseCommitResponse = true;
        assertThatThrownBy(() -> db.adapter.publish(db.build)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(db.active).isEqualTo(id(99).toString());
        assertThat(db.jobReady).isTrue();
        db.loseCommitResponse = false;
        assertThatThrownBy(() -> db.adapter.publish(db.build)).hasMessage("BUILD_SOURCE_CHANGED");
        assertThat(db.active).isEqualTo(id(99).toString());
    }

    @Test void algorithmVersionIsPartOfPersistentChunkIdentityAndReaderSelection() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/db/migration/V35__public_knowledge_index.sql"));
        assertThat(schema).contains("document_id, document_version, chunker_version, ordinal",
                "uk_knowledge_generation_attempt (build_job_id, build_lease_token)", "fk_knowledge_generation_job");
        String reader = Files.readString(Path.of("src/main/java/com/aifriend/retrieval/infrastructure/JdbcKnowledgeAdapter.java"));
        assertThat(reader).contains("k.source_start, k.source_end, k.content_digest, k.chunker_version");
    }

    private static byte[] sha(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }

    static final class Simulation {
        final Instant now = Instant.parse("2026-09-10T00:00:00Z");
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        final JdbcKnowledgeBuildSourceAdapter sources = mock(JdbcKnowledgeBuildSourceAdapter.class);
        final Map<String, Map<String, Object>> generations = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> chunks = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> embeddings = new LinkedHashMap<>();
        final Map<String, List<String>> manifest = new LinkedHashMap<>();
        final List<String> sql = new ArrayList<>();
        final Build build;
        List<KnowledgeDocument> oldDocuments=List.of();
        final JdbcKnowledgeGenerationAdapter adapter;
        String active = id(88).toString();
        boolean jobReady, sourceReady, loseCommitResponse, inTransaction;
        long activeVersion = 1;
        int writes, failAtWrite;
        String rejectCas = "";
        Checkpoint old;

        @SuppressWarnings({"rawtypes", "unchecked"})
        Simulation(boolean vector) throws Exception {
            var chunker = new KnowledgeChunker(400, 60, 600);
            var claim = new Claim(KnowledgeImportJob.pending(id(1), now, now.plusSeconds(600))
                    .claim(now, id(2), Duration.ofSeconds(30)), 5, 3);
            var specification = new BuildSpecification(vector ? Optional.of(new EmbeddingProfile("p1", 2)) : Optional.empty(),
                    KnowledgeTokenizer.VERSION, chunker.version());
            var documents = List.of(new KnowledgeDocument(id(10), "public-10", 2, "说明", "zh-CN", 1, 10, "新的守护说明"),
                    new KnowledgeDocument(id(11), "public-11", 1, "说明", "zh-CN", 1, 10, "未修改的消息说明"));
            var source = new Source(claim, specification, id(10), 2, documents);
            build = new Build(source, source.snapshot(id(99), documents.stream().flatMap(d -> chunker.chunk(d).stream()).toList()));
            generations.put(active, new HashMap<>(Map.of("status", "ACTIVE")));
            when(sources.loadLocked(any())).thenAnswer(c -> {
                if (jobReady) { throw new ConcurrencyFailureException("BUILD_SOURCE_CHANGED"); }
                return source;
            });
            when(transactions.getTransaction(any())).thenAnswer(c -> {
                assertThat(inTransaction).isFalse();
                inTransaction = true;
                old = checkpoint(); writes = 0;
                return new SimpleTransactionStatus();
            });
            doAnswer(c -> { restore(old); inTransaction = false; return null; }).when(transactions).rollback(any());
            doAnswer(c -> {
                inTransaction = false;
                if (loseCommitResponse) { throw new DataAccessResourceFailureException("simulated response lost"); }
                return null;
            }).when(transactions).commit(any());
            doAnswer(c -> map(c.getArgument(0), c.getArgument(1), new Object[0]))
                    .when(jdbc).query(anyString(), any(RowMapper.class));
            doAnswer(c -> map(c.getArgument(0), c.getArgument(1), Arrays.copyOfRange(c.getArguments(), 2, c.getArguments().length)))
                    .when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
            when(jdbc.queryForObject(eq("SELECT UTC_TIMESTAMP(3)"), eq(Timestamp.class))).thenReturn(Timestamp.from(now));
            when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenAnswer(c -> {
                String gen = (String) c.getArguments()[2];
                return embeddings.keySet().stream().filter(k -> k.startsWith(gen + ":")).count();
            });
            doAnswer(c -> update(c.getArgument(0), Arrays.copyOfRange(c.getArguments(), 1, c.getArguments().length)))
                    .when(jdbc).update(anyString(), any(Object[].class));
            doAnswer(c -> update(c.getArgument(0), new Object[0])).when(jdbc).update(anyString());
            doAnswer(c -> {
                String query = c.getArgument(0);
                List<Object[]> rows = c.getArgument(1);
                for (Object[] args : rows) { batch(query, args); }
                int[] result = new int[rows.size()];
                Arrays.fill(result, 1); return result;
            }).when(jdbc).batchUpdate(anyString(), anyList());
            adapter = new JdbcKnowledgeGenerationAdapter(jdbc, transactions, sources);
        }

        void fill() { adapter.begin(build); adapter.stage(build, ids(), vectors()); }
        void seedReadableOldIndex() throws Exception {
            var current=build.source().documents();
            oldDocuments=List.of(new KnowledgeDocument(id(10),"public-10",1,"说明","zh-CN",1,10,"旧的守护说明"),current.get(1));
            var chunker=new KnowledgeChunker(400,60,600);
            var parts=oldDocuments.stream().flatMap(d->chunker.chunk(d).stream()).toList();
            var version=new com.aifriend.retrieval.domain.IndexVersion(id(88),2,Optional.empty(),KnowledgeTokenizer.VERSION,chunker.version());
            var prior=new com.aifriend.retrieval.application.KnowledgeRepositoryPort.Snapshot(version,oldDocuments,parts);
            var header=generations.get(active);header.put("id",active);header.put("corpus_revision",2L);
            header.put("tokenizer_version",KnowledgeTokenizer.VERSION);header.put("chunker_version",chunker.version());
            header.put("corpus_digest",new KnowledgeSnapshotDigest().calculate(prior));header.put("document_count",2);header.put("chunk_count",parts.size());
            for(var c:parts) {
                var values=new HashMap<String,Object>();
                values.put("chunk_id",c.id().toString());values.put("document_id",c.documentId().toString());values.put("document_version",c.documentVersion());
                values.put("ordinal",c.ordinal());values.put("chunker_version",c.chunkerVersion());values.put("heading",c.heading());
                values.put("chunk_text",c.text());values.put("source_start",c.sourceStart());values.put("source_end",c.sourceEnd());values.put("content_digest",sha(c.text()));
                chunks.put(c.id().toString(),values);
            }
            manifest.put(active,new ArrayList<>(parts.stream().map(c->c.id().toString()).toList()));
        }
        List<UUID> ids() { return build.snapshot().chunks().stream().map(c -> c.id()).toList(); }
        Map<UUID, float[]> vectors() {
            var result = new HashMap<UUID, float[]>();
            if (build.snapshot().version().embeddingProfile().isPresent()) { ids().forEach(id -> result.put(id, new float[] {1, 2})); }
            return result;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        List<?> map(String query, RowMapper mapper, Object[] args) throws Exception {
            assertThat(query.chars().filter(ch -> ch == '?').count()).isEqualTo(args.length);
            var data = new ArrayList<Map<String, Object>>();
            if (query.contains("active_id")) {
                var header = new HashMap<>(generations.get(active));
                header.put("active_id", active); header.put("generation_id", active); data.add(header);
            } else if (query.contains("SELECT DISTINCT")) {
                for (var document : args[0].equals(id(88).toString()) && !oldDocuments.isEmpty() ? oldDocuments : build.source().documents()) {
                    var row = new HashMap<String, Object>();
                    row.put("document_id", document.id().toString()); row.put("source_key", document.sourceKey());
                    row.put("document_status", "ACTIVE"); row.put("active_version", document.id().equals(id(10)) ? activeVersion : 1L);
                    row.put("version", document.version()); row.put("version_status", "READY"); row.put("title", document.title());
                    row.put("locale", document.locale()); row.put("min_app_version", 1); row.put("max_app_version", 10);
                    row.put("original_text", document.text()); row.put("content_digest", sha(document.text()));
                    data.add(row);
                }
            } else if (query.contains("SELECT BIN_TO_UUID(g.id) id")) {
                for (String id : generations.keySet()) { if (!id.equals(active)) { data.add(Map.of("id", id)); } }
            } else if (query.contains("FROM knowledge_index_generation WHERE")) {
                if (generations.containsKey(args[0])) { data.add(generations.get(args[0])); }
            } else if (query.contains("chunk_text")) {
                List<String> ids = query.contains("knowledge_generation_chunk")
                        ? manifest.getOrDefault(args[0], List.of()) : Arrays.stream(args).map(Object::toString).toList();
                for (String id : ids) { if (chunks.containsKey(id)) { data.add(chunks.get(id)); } }
            } else if (query.contains("vector_blob")) {
                List<String> ids = query.contains("LEFT JOIN") ? manifest.getOrDefault(args[0], List.of())
                        : Arrays.stream(args).skip(1).map(Object::toString).toList();
                for (String id : ids) {
                    Map<String, Object> vector = embeddings.get(args[0] + ":" + id);
                    if (vector != null) { data.add(vector); }
                    else if (query.contains("LEFT JOIN")) { data.add(Map.of("chunk_id", id)); }
                }
            } else { throw new AssertionError("Unexpected query: " + query); }
            var result = new ArrayList<>();
            for (var row : data) { result.add(mapper.mapRow(row(row), result.size())); }
            return result;
        }

        void beforeWrite(String query, Object[] args) {
            assertThat(query.chars().filter(ch -> ch == '?').count()).isEqualTo(args.length);
            sql.add(query);
            if (++writes == failAtWrite) { throw new DataAccessResourceFailureException("simulated write failure"); }
        }

        int update(String query, Object[] args) {
            beforeWrite(query, args);
            if (query.startsWith("INSERT INTO knowledge_index_generation")) {
                var row = new HashMap<String, Object>();
                String[] columns = {"id", "build_job_id", "build_lease_token", "corpus_revision", "profile_id", "dimension",
                        "tokenizer_version", "chunker_version", "corpus_digest", "document_count", "chunk_count"};
                for (int i = 0; i < columns.length; i++) { row.put(columns[i], args[i]); }
                row.put("status", "BUILDING"); generations.put((String) args[0], row);
            } else if (query.startsWith("DELETE FROM knowledge_embedding")) {
                embeddings.keySet().removeIf(k -> k.startsWith(args[0] + ":"));
            } else if (query.startsWith("DELETE FROM knowledge_generation_chunk")) {
                manifest.remove(args[0]);
            } else if (query.startsWith("DELETE FROM knowledge_index_generation")) {
                assertThat(args[0]).isNotEqualTo(active);
                generations.remove(args[0]);
            } else if (query.startsWith("UPDATE knowledge_document_version SET status='READY'")) {
                sourceReady = true;
            } else if (query.startsWith("UPDATE knowledge_document SET")) {
                activeVersion = (Long) args[0];
            } else if (query.startsWith("UPDATE knowledge_document_version SET status='RETIRED'")) {
                // 旧版本退休只记录SQL；sourceReady与活动版本用于检查原子可见性。
            } else if (query.startsWith("UPDATE knowledge_index_generation SET status='RETIRED'")) {
                generations.get(active).put("status", "RETIRED");
            } else if (query.startsWith("UPDATE knowledge_index_generation SET status='ACTIVE'")) {
                generations.get(args[0]).put("status", "ACTIVE");
            } else if (query.startsWith("UPDATE knowledge_index_control")) {
                assertThat(query).contains("version=?", "corpus_revision=?", "lease_token=UUID_TO_BIN(?)", "lease_until>UTC_TIMESTAMP(3)");
                if (rejectCas.equals("control")) { return 0; }
                active = (String) args[0];
            } else if (query.startsWith("UPDATE knowledge_import_job")) {
                assertThat(query).contains("version=?", "lease_until>UTC_TIMESTAMP(3)", "deadline>UTC_TIMESTAMP(3)");
                if (rejectCas.equals("job")) { return 0; }
                jobReady = true;
            } else { throw new AssertionError("Unexpected write: " + query); }
            return 1;
        }

        void batch(String query, Object[] args) {
            beforeWrite(query, args);
            var row = new HashMap<String, Object>();
            if (query.startsWith("INSERT INTO knowledge_chunk")) {
                String[] names = {"chunk_id", "document_id", "document_version", "ordinal", "chunker_version",
                        "heading", "chunk_text", "source_start", "source_end", "content_digest"};
                for (int i = 0; i < names.length; i++) { row.put(names[i], args[i]); }
                chunks.putIfAbsent((String) args[0], row);
            } else if (query.startsWith("INSERT INTO knowledge_generation_chunk")) {
                var ids = manifest.computeIfAbsent((String) args[0], unused -> new ArrayList<>());
                if (!ids.contains(args[1])) { ids.add((String) args[1]); }
            } else if (query.startsWith("INSERT INTO knowledge_embedding")) {
                row.put("chunk_id", args[1]); row.put("dimension", args[2]); row.put("vector_blob", args[3]); row.put("digest", args[4]);
                embeddings.putIfAbsent(args[0] + ":" + args[1], row);
            } else { throw new AssertionError("Unexpected batch"); }
        }

        ResultSet row(Map<String, Object> values) throws Exception {
            var rs = mock(ResultSet.class);
            when(rs.getString(anyString())).thenAnswer(c -> values.get(c.getArgument(0)));
            when(rs.getBytes(anyString())).thenAnswer(c -> values.get(c.getArgument(0)));
            when(rs.getObject(anyString())).thenAnswer(c -> values.get(c.getArgument(0)));
            when(rs.getTimestamp(anyString())).thenAnswer(c -> values.get(c.getArgument(0)));
            when(rs.getInt(anyString())).thenAnswer(c -> ((Number) values.getOrDefault(c.getArgument(0), 0)).intValue());
            when(rs.getLong(anyString())).thenAnswer(c -> ((Number) values.getOrDefault(c.getArgument(0), 0L)).longValue());
            return rs;
        }

        Checkpoint checkpoint() {
            var lists = new LinkedHashMap<String, List<String>>();
            manifest.forEach((key, value) -> lists.put(key, new ArrayList<>(value)));
            return new Checkpoint(copy(generations), copy(chunks), copy(embeddings), lists, active, jobReady, sourceReady, activeVersion);
        }
        void restore(Checkpoint state) {
            generations.clear(); generations.putAll(copy(state.generations));
            chunks.clear(); chunks.putAll(copy(state.chunks));
            embeddings.clear(); embeddings.putAll(copy(state.embeddings));
            manifest.clear(); state.manifest.forEach((key, value) -> manifest.put(key, new ArrayList<>(value)));
            active = state.active; jobReady = state.jobReady; sourceReady = state.sourceReady; activeVersion = state.activeVersion;
        }
        static Map<String, Map<String, Object>> copy(Map<String, Map<String, Object>> source) {
            var result = new LinkedHashMap<String, Map<String, Object>>();
            source.forEach((key, value) -> result.put(key, new HashMap<>(value))); return result;
        }
        record Checkpoint(Map<String, Map<String, Object>> generations, Map<String, Map<String, Object>> chunks,
                Map<String, Map<String, Object>> embeddings, Map<String, List<String>> manifest,
                String active, boolean jobReady, boolean sourceReady, long activeVersion) { }
    }
}
