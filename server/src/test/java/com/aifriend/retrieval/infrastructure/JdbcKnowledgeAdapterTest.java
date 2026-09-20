package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.aifriend.retrieval.application.KnowledgeRepositoryPort.Snapshot;
import com.aifriend.retrieval.application.KnowledgeRetrievalException;
import com.aifriend.retrieval.domain.*;

/** 模拟JDBC结果及事务边界；不宣称已验证MySQL SQL/事务隔离。 */
class JdbcKnowledgeAdapterTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final EmbeddingProfile profile = new EmbeddingProfile("p1", 2);
    private final IndexVersion version = new IndexVersion(id(9), 1, Optional.of(profile), "t1", "c1");
    private final KnowledgeDocument document = new KnowledgeDocument(id(1), "guide", 1, "说明", "zh-CN", 1, 10, "开启守护");
    private final KnowledgeChunk chunk = new KnowledgeChunk(id(2), id(1), 1, 0, "", "开启守护", 0, 4, "c1");
    private final Snapshot snapshot = new Snapshot(version, List.of(document), List.of(chunk));
    private final Map<String, Object> header = new HashMap<>();
    private final Map<String, Object> source = new HashMap<>();
    private final Map<String, Object> part = new HashMap<>();
    private final Map<String, Object> vector = new HashMap<>();
    private final List<String> sqlSeen = new ArrayList<>();
    private boolean missingControl;
    private boolean missingChunk;
    private boolean failDatabase;
    private JdbcKnowledgeAdapter adapter;

    @BeforeEach void setup() throws Exception {
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        header.putAll(Map.of("active_id", id(9).toString(), "generation_id", id(9).toString(), "corpus_revision", 1L,
                "profile_id", "p1", "dimension", 2, "tokenizer_version", "t1", "chunker_version", "c1",
                "corpus_digest", new KnowledgeSnapshotDigest().calculate(snapshot), "document_count", 1, "chunk_count", 1));
        header.put("status", "ACTIVE");
        source.putAll(Map.of("document_id", id(1).toString(), "source_key", "guide", "document_status", "ACTIVE",
                "active_version", 1L, "version", 1L, "title", "说明", "locale", "zh-CN",
                "min_app_version", 1, "max_app_version", 10, "original_text", "开启守护"));
        source.putAll(Map.of("content_digest", hash("开启守护"), "chunker_version", "c1", "version_status", "READY"));
        part.putAll(Map.of("chunk_id", id(2).toString(), "document_id", id(1).toString(), "document_version", 1L,
                "ordinal", 0, "heading", "", "chunk_text", "开启守护", "source_start", 0, "source_end", 4,
                "content_digest", hash("开启守护"), "chunker_version", "c1"));
        var stored = new KnowledgeVectorCodec().encode(profile, id(9), id(2), new float[] {1, 2});
        vector.putAll(Map.of("chunk_id", id(2).toString(), "dimension", 2, "vector_blob", stored.bytes(), "digest", stored.digest()));
        stubQueries();
        adapter = new JdbcKnowledgeAdapter(jdbc, transactions);
    }

    @Test void validCompleteSnapshotAndVectorsRoundTripWithIndependentTransactions() {
        assertThat(adapter.readActive()).contains(snapshot);
        assertThat(adapter.isCurrent(version, List.of(chunk))).isTrue();
        assertThat(adapter.load(version).get(id(2))).containsExactly(1, 2);
        var definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions, times(3)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues()).allSatisfy(def -> {
            assertThat(def.isReadOnly()).isTrue();
            assertThat(def.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(def.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(def.getTimeout()).isEqualTo(2);
        });
        verify(transactions, times(3)).commit(any());
        assertThat(sqlSeen).anyMatch(sql -> sql.contains("LIMIT 101"))
                .anyMatch(sql -> sql.contains("LIMIT 2001"));
    }

    @Test void scopedEvidenceUsesOneFreshCompleteReadAndKeepsScopeBoundaries() {
        assertThat(adapter.isCurrentForScope(version,List.of(chunk),"zh-CN",1)).isTrue();
        assertThat(sqlSeen).hasSize(3);
        verify(transactions,times(1)).getTransaction(any());
        verify(transactions,times(1)).commit(any());
        assertThat(adapter.isCurrentForScope(version,List.of(chunk),"zh-CN",10)).isTrue();
        assertThat(adapter.isCurrentForScope(version,List.of(chunk),"en-US",1)).isFalse();
        assertThat(adapter.isCurrentForScope(version,List.of(chunk),"zh-CN",11)).isFalse();
        source.put("min_app_version",2);
        // Changing metadata without its corpus digest is corruption, not an allowed scope fallback.
        invalidIndex(() -> adapter.isCurrentForScope(version,List.of(chunk),"zh-CN",1));
    }

    @Test void scopedEvidenceNeverReusesPriorReadAfterDeletionOrStorageFailure() {
        assertThat(adapter.isCurrentForScope(version,List.of(chunk),"zh-CN",1)).isTrue();
        source.put("document_status","DELETED");
        assertThat(adapter.isCurrentForScope(version,List.of(chunk),"zh-CN",1)).isFalse();
        source.put("document_status","ACTIVE");
        part.put("content_digest",new byte[32]);
        invalidIndex(() -> adapter.isCurrentForScope(version,List.of(chunk),"zh-CN",1));
        failDatabase=true;
        assertThatThrownBy(() -> adapter.isCurrentForScope(version,List.of(chunk),"zh-CN",1))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test void scopedEvidenceRejectsForeignChunkOrGeneration() {
        var other=new KnowledgeChunk(id(3),id(1),1,0,"","开启守护",0,4,"c1");
        assertThat(adapter.isCurrentForScope(version,List.of(other),"zh-CN",1)).isFalse();
        assertThat(adapter.isCurrentForScope(new IndexVersion(id(8),1,Optional.of(profile),"t1","c1"),
                List.of(chunk),"zh-CN",1)).isFalse();
    }

    @Test void noActivePointerIsEmptyButMissingControlIsCorruption() {
        header.remove("active_id");
        assertThat(adapter.readActive()).isEmpty();
        assertThat(sqlSeen).hasSize(1);
        missingControl = true;
        invalidIndex(() -> adapter.readActive());
    }

    @Test void missingManifestRowsAndWrongCorpusDigestRejectEntireSnapshot() {
        missingChunk = true;
        invalidIndex(() -> adapter.readActive());
        missingChunk = false;
        header.put("corpus_digest", new byte[32]);
        invalidIndex(() -> adapter.readActive());
    }

    @Test void sourceAndChunkDigestCorruptionCannotBecomeEmptyResults() throws Exception {
        source.put("content_digest", new byte[32]);
        invalidIndex(() -> adapter.readActive());
        source.put("content_digest", hash("开启守护"));
        part.put("content_digest", new byte[32]);
        invalidIndex(() -> adapter.readActive());
    }

    @Test void deletionBetweenRetrievalAndFinalCheckIsVisibleToFreshRead() {
        assertThat(adapter.readActive()).isPresent();
        source.put("document_status", "DELETED");
        assertThat(adapter.isCurrent(version, List.of(chunk))).isFalse();
        assertThatThrownBy(() -> adapter.readActive()).isInstanceOf(KnowledgeRetrievalException.class)
                .hasMessage("SOURCE_CHANGED");
    }

    @Test void deletedSourceCanOnlyBeReadByInternalRebuildAndStillNeedsOriginalIntegrity() {
        source.put("document_status","DELETED");source.remove("active_version");
        source.put("deleted_at",java.sql.Timestamp.from(java.time.Instant.parse("2026-09-11T09:00:00Z")));
        assertThatThrownBy(adapter::readActive).hasMessage("SOURCE_CHANGED");
        var historical=adapter.deletionSourceInTransaction().orElseThrow();
        assertThat(historical.snapshot()).isEqualTo(snapshot);assertThat(historical.deleted()).containsExactly(id(1));
        source.put("content_digest",new byte[32]);
        assertThatThrownBy(adapter::deletionSourceInTransaction).hasMessage("INDEX_INVALID");
    }

    @Test void documentVersionChangeAndGenerationChangeDoNotReuseOldIdentity() {
        source.put("active_version", 2L);
        assertThat(adapter.isCurrent(version, List.of(chunk))).isFalse();
        source.put("active_version", 1L);
        var other = new IndexVersion(id(8), 1, Optional.of(profile), "t1", "c1");
        assertThat(adapter.isCurrent(other, List.of(chunk))).isFalse();
        assertThatThrownBy(() -> adapter.load(other)).hasMessage("SOURCE_CHANGED");
    }

    @Test void foreignEvidenceCannotPassFinalCheck() {
        var other = new KnowledgeChunk(id(3), id(1), 1, 0, "", "开启守护", 0, 4, "c1");
        assertThat(adapter.isCurrent(version, List.of(other))).isFalse();
    }

    @Test void databaseFailurePropagatesAndRollsBackInsteadOfReturningCache() {
        assertThat(adapter.readActive()).isPresent();
        failDatabase = true;
        assertThatThrownBy(() -> adapter.readActive()).isInstanceOf(DataAccessResourceFailureException.class);
        assertThatThrownBy(() -> adapter.isCurrent(version, List.of(chunk))).isInstanceOf(DataAccessResourceFailureException.class);
        verify(transactions, times(2)).rollback(any());
    }

    @Test void missingVectorWrongDimensionOrCorruptedBlobRejectWholeVectorLoad() {
        vector.remove("dimension");
        invalidIndex(() -> adapter.load(version));
        vector.put("dimension", 3);
        invalidIndex(() -> adapter.load(version));
        vector.put("dimension", 2);
        vector.put("digest", new byte[32]);
        invalidIndex(() -> adapter.load(version));
    }

    @Test void invalidActiveHeaderCannotDowngradeToEmptyOrKeyword() {
        header.put("status", "BUILDING");
        invalidIndex(() -> adapter.readActive());
        header.put("status", "ACTIVE");
        header.remove("dimension");
        invalidIndex(() -> adapter.readActive());
    }

    @Test void countLimitsAreCheckedBeforeLargeSourceQueries() {
        header.put("document_count", 101);
        invalidIndex(() -> adapter.readActive());
        assertThat(sqlSeen).hasSize(1);
        header.put("document_count", 1);
        header.put("chunk_count", 2001);
        invalidIndex(() -> adapter.readActive());
        assertThat(sqlSeen).hasSize(2);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubQueries() {
        org.mockito.stubbing.Answer answer = call -> {
            if (failDatabase) { throw new DataAccessResourceFailureException("simulated"); }
            String sql = call.getArgument(0);
            sqlSeen.add(sql);
            RowMapper mapper = call.getArgument(1);
            List<Map<String, Object>> rows;
            if (sql.contains("FROM knowledge_index_control")) {
                rows = missingControl ? List.of() : List.of(header);
            } else if (sql.contains("SELECT DISTINCT")) {
                rows = List.of(source);
            } else if (sql.contains("LEFT JOIN knowledge_embedding")) {
                rows = List.of(vector);
            } else {
                rows = missingChunk ? List.of() : List.of(part);
            }
            var mapped = new ArrayList<>();
            for (var row : rows) { mapped.add(mapper.mapRow(result(row), mapped.size())); }
            return mapped;
        };
        doAnswer(answer).when(jdbc).query(anyString(), any(RowMapper.class));
        doAnswer(answer).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    private ResultSet result(Map<String, Object> values) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(anyString())).thenAnswer(c -> values.get(c.getArgument(0)));
        when(rs.getObject(anyString())).thenAnswer(c -> values.get(c.getArgument(0)));
        when(rs.getBytes(anyString())).thenAnswer(c -> values.get(c.getArgument(0)));
        when(rs.getTimestamp(anyString())).thenAnswer(c -> values.get(c.getArgument(0)));
        when(rs.getInt(anyString())).thenAnswer(c -> ((Number) values.getOrDefault(c.getArgument(0), 0)).intValue());
        when(rs.getLong(anyString())).thenAnswer(c -> ((Number) values.getOrDefault(c.getArgument(0), 0L)).longValue());
        return rs;
    }

    private void invalidIndex(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(KnowledgeRetrievalException.class).hasMessage("INDEX_INVALID");
    }
    private byte[] hash(String text) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    }
    private static UUID id(int n) { return new UUID(0, n); }
}
