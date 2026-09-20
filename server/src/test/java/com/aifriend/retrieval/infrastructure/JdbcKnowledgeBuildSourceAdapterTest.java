package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.aifriend.retrieval.application.KnowledgeImportLeasePort.Claim;
import com.aifriend.retrieval.domain.KnowledgeImportJob;
import com.aifriend.retrieval.domain.RetrievalQuery;

class JdbcKnowledgeBuildSourceAdapterTest {
    private final Instant now = Instant.parse("2026-09-09T00:00:00Z");
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final KnowledgeChunker chunker = new KnowledgeChunker(400, 60, 600);
    private final Claim claim = new Claim(KnowledgeImportJob.pending(id(1), now, now.plusSeconds(600))
            .claim(now, id(2), Duration.ofSeconds(30)), 5, 3);
    private final Map<String, Object> control = new HashMap<>();
    private final Map<String, Object> job = new HashMap<>();
    private final Map<String, Object> pending = new HashMap<>();
    private List<Map<String, Object>> active = new ArrayList<>();
    private JdbcKnowledgeBuildSourceAdapter adapter;

    @BeforeEach @SuppressWarnings({"rawtypes", "unchecked"})
    void setup() throws Exception {
        when(transactions.getTransaction(any())).thenAnswer(c -> new SimpleTransactionStatus());
        control.putAll(Map.of("version", 5L, "corpus_revision", 3L, "lease_job_id", id(1).toString(),
                "lease_token", id(2).toString(), "lease_until", Timestamp.from(now.plusSeconds(30))));
        job.putAll(Map.of("document_id", id(10).toString(), "document_version", 2L, "status", "PROCESSING", "version", 2L,
                "lease_token", id(2).toString(), "lease_until", Timestamp.from(now.plusSeconds(30)),
                "deadline", Timestamp.from(now.plusSeconds(600)), "tokenizer_version", KnowledgeTokenizer.VERSION,
                "chunker_version", chunker.version()));
        pending.putAll(source(10, 2, "新版本守护说明", "BUILDING"));
        active.add(source(10, 1, "旧版本说明", "READY"));
        active.add(source(11, 1, "未修改的消息说明", "READY"));
        doAnswer(c -> {
            String query = c.getArgument(0);
            RowMapper mapper = c.getArgument(1);
            if (query.contains("knowledge_index_control")) { return List.of(mapper.mapRow(row(control), 0)); }
            assertThat(query).contains("LEFT JOIN", "LIMIT 101");
            var results = new ArrayList<>();
            for (var source : active) { results.add(mapper.mapRow(row(source), results.size())); }
            return results;
        }).when(jdbc).query(anyString(), any(RowMapper.class));
        doAnswer(c -> {
            String query = c.getArgument(0);
            RowMapper mapper = c.getArgument(1);
            return List.of(mapper.mapRow(row(query.contains("knowledge_import_job") ? job : pending), 0));
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(jdbc.queryForObject(eq("SELECT UTC_TIMESTAMP(3)"), eq(Timestamp.class))).thenReturn(Timestamp.from(now));
        adapter = new JdbcKnowledgeBuildSourceAdapter(jdbc, transactions);
    }

    @Test void smokeCompleteSourcesReachRealChunkerAndBm25WithoutOmittingUnchangedDocument() {
        var source = adapter.load(claim);
        assertThat(source.documents()).hasSize(2);
        assertThat(source.documents().stream().map(d -> d.text())).containsExactlyInAnyOrder("新版本守护说明", "未修改的消息说明");
        var chunks = source.documents().stream().flatMap(d -> chunker.chunk(d).stream()).toList();
        var snapshot = source.snapshot(id(99), chunks);
        var index = new Bm25KeywordIndex(snapshot, new KnowledgeTokenizer(), 1.2, .75, 128L * 1024 * 1024);
        assertThat(index.search(new RetrievalQuery("消息", "zh-CN", 1, 4), 20))
                .anyMatch(hit -> hit.chunk().documentId().equals(id(11)));
        assertThat(snapshot.version().corpusRevision()).isEqualTo(3);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void targetFirstVersionCanJoinOtherwiseEmptyCorpus() {
        active = List.of();
        pending.put("active_version", 0L);
        assertThat(adapter.load(claim).documents()).hasSize(1);
    }

    @Test void corpusRevisionOrControlVersionChangeInvalidatesBuildBeforeSourceRead() {
        control.put("corpus_revision", 4L);
        assertThatThrownBy(() -> adapter.load(claim)).isInstanceOf(ConcurrencyFailureException.class);
        control.put("corpus_revision", 3L);
        control.put("version", 6L);
        assertThatThrownBy(() -> adapter.load(claim)).isInstanceOf(ConcurrencyFailureException.class);
    }

    @Test void staleWorkerAndNewerPublishedTargetAreRejected() {
        job.put("lease_token", id(3).toString());
        assertThatThrownBy(() -> adapter.load(claim)).isInstanceOf(ConcurrencyFailureException.class);
        job.put("lease_token", id(2).toString());
        pending.put("active_version", 3L);
        assertThatThrownBy(() -> adapter.load(claim)).isInstanceOf(ConcurrencyFailureException.class);
    }

    @Test void deletedOrCorruptedSourceCannotBePartiallySkipped() {
        active.get(1).put("document_status", "DELETED");
        assertThatThrownBy(() -> adapter.load(claim)).isInstanceOf(ConcurrencyFailureException.class);
        active.get(1).put("document_status", "ACTIVE");
        active.get(1).put("content_digest", new byte[32]);
        assertThatIllegalArgumentException().isThrownBy(() -> adapter.load(claim)).withMessage("INVALID_SOURCE_DIGEST");
    }

    @Test void missingActiveVersionFromLeftJoinFailsRatherThanOmittingDocument() {
        active.get(1).remove("version_status");
        assertThatIllegalArgumentException().isThrownBy(() -> adapter.load(claim)).withMessage("INVALID_ACTIVE_SOURCE");
    }

    @Test void leaseExpiryDuringSourceReadFailsFinalTimeCheck() {
        when(jdbc.queryForObject(eq("SELECT UTC_TIMESTAMP(3)"), eq(Timestamp.class)))
                .thenReturn(Timestamp.from(now), Timestamp.from(now.plusSeconds(30)));
        assertThatThrownBy(() -> adapter.load(claim)).isInstanceOf(ConcurrencyFailureException.class);
    }

    @Test void modelProfileMustBeCompleteAndTargetAlgorithmMustAgree() {
        job.put("profile_id", "p1");
        assertThatIllegalArgumentException().isThrownBy(() -> adapter.load(claim)).withMessage("INVALID_BUILD_PROFILE");
        job.remove("profile_id");
        pending.put("chunker_version", "wrong");
        assertThatIllegalArgumentException().isThrownBy(() -> adapter.load(claim)).withMessage("INVALID_TARGET_SOURCE");
    }

    @Test void databaseFailureDoesNotBuildFromRememberedSource() {
        assertThat(adapter.load(claim).documents()).hasSize(2);
        when(jdbc.queryForObject(eq("SELECT UTC_TIMESTAMP(3)"), eq(Timestamp.class)))
                .thenThrow(new DataAccessResourceFailureException("simulated"));
        assertThatThrownBy(() -> adapter.load(claim)).isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test void overflowingOrIncompleteSourceCannotBecomeValidSnapshot() throws Exception {
        active = new ArrayList<>();
        for (int n = 100; n < 201; n++) { active.add(source(n, 1, "公开说明", "READY")); }
        assertThatIllegalArgumentException().isThrownBy(() -> adapter.load(claim)).withMessage("BUILD_SOURCE_LIMIT");
        active = List.of();
        var source = adapter.load(claim);
        assertThatIllegalArgumentException().isThrownBy(() -> source.snapshot(id(99), List.of()))
                .withMessage("INCOMPLETE_CORPUS");
    }

    private Map<String, Object> source(int id, long version, String text, String state) throws Exception {
        var data = new HashMap<String, Object>();
        data.putAll(Map.of("document_id", id(id).toString(), "source_key", "source-" + id, "document_status", "ACTIVE",
                "active_version", 1L, "version", version, "title", "说明", "locale", "zh-CN",
                "min_app_version", 1, "max_app_version", 10, "original_text", text));
        data.put("content_digest", MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        data.put("version_status", state);
        data.put("chunker_version", chunker.version());
        return data;
    }

    private ResultSet row(Map<String, Object> values) throws Exception {
        var rs = mock(ResultSet.class);
        when(rs.getString(anyString())).thenAnswer(c -> values.get(c.getArgument(0)));
        when(rs.getBytes(anyString())).thenAnswer(c -> values.get(c.getArgument(0)));
        when(rs.getTimestamp(anyString())).thenAnswer(c -> values.get(c.getArgument(0)));
        when(rs.getObject(anyString())).thenAnswer(c -> values.get(c.getArgument(0)));
        when(rs.getLong(anyString())).thenAnswer(c -> ((Number) values.getOrDefault(c.getArgument(0), 0L)).longValue());
        when(rs.getInt(anyString())).thenAnswer(c -> ((Number) values.getOrDefault(c.getArgument(0), 0)).intValue());
        return rs;
    }
    private static UUID id(int n) { return new UUID(0, n); }
}
