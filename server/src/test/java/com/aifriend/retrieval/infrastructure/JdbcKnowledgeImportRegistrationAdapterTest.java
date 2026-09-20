package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.aifriend.retrieval.application.KnowledgeImportException;
import com.aifriend.retrieval.application.KnowledgeImportRegistrationPort.BuildSpecification;
import com.aifriend.retrieval.application.KnowledgeImportRegistrationPort.Receipt;
import com.aifriend.retrieval.domain.EmbeddingProfile;
import com.aifriend.retrieval.domain.KnowledgeImportJob;
import com.aifriend.retrieval.domain.KnowledgeImportRequest;

/** SQL操作模拟器用于Java事务/幂等分支，不代替真实MySQL兼容性验证。 */
class JdbcKnowledgeImportRegistrationAdapterTest {
    private static final BuildSpecification LEXICAL = new BuildSpecification(Optional.empty(), "t1", "c1");
    private static KnowledgeImportRequest request(String key, String text) {
        return new KnowledgeImportRequest("public-guide", "操作说明", text, "zh-CN", 1, 10, key);
    }
    private static KnowledgeImportRequest request() { return request("import-request-key-0001", "公开说明😀\n原文"); }

    @Test void registerPersistsPendingVersionWithoutActivatingAnything() throws Exception {
        var db = new Simulation();
        Receipt result = db.adapter().register(request(), LEXICAL);
        assertThat(result.state()).isEqualTo(KnowledgeImportJob.State.PENDING);
        assertThat(result.attempts()).isZero();
        assertThat(result.documentVersion()).isEqualTo(1);
        assertThat(db.documents.get("public-guide").get("active_version")).isNull();
        assertThat(db.originals.get(result.documentId() + ":1")).isEqualTo(request().text());
        assertThat(db.jobs).hasSize(1);
        assertThat(db.adapter().find(result.id())).contains(result);
        assertThat(db.sql).noneMatch(sql -> sql.contains("knowledge_embedding") || sql.contains("SET active_generation_id"));
    }

    @org.junit.jupiter.params.ParameterizedTest(name="frozen import {0}")
    @org.junit.jupiter.params.provider.MethodSource("frozenRegistrationCases")
    void frozenRegistrationPreservesOriginalIdentity(FrozenKnowledgeCases.Scenario sample) throws Exception {
        var db=new Simulation();var first=db.adapter().register(request(),LEXICAL);int writes=db.writes;
        switch(sample.operation()) {
            case "REPEAT_IMPORT"->{
                assertThat(sample.expected()).isEqualTo("SAME_JOB_VERSION_NO_DUPLICATE");
                assertThat(db.adapter().register(request(),LEXICAL)).isEqualTo(first);
            }
            case "IMPORT_KEY_CONFLICT"->{
                assertThat(sample.expected()).isEqualTo("IDEMPOTENCY_CONFLICT");
                assertThatThrownBy(()->db.adapter().register(request(request().idempotencyKey(),"不同的公开说明"),LEXICAL))
                        .isInstanceOf(KnowledgeImportException.class).hasMessage("IDEMPOTENCY_CONFLICT");
            }
            default->throw new AssertionError("Unimplemented frozen import scenario");
        }
        assertThat(db.writes).isEqualTo(writes);assertThat(db.jobs).hasSize(1);assertThat(db.documents).hasSize(1);
        assertThat(db.originals).hasSize(1);assertThat(db.originals.get(first.documentId()+":1")).isEqualTo(request().text());
        assertThat(db.adapter().find(first.id())).contains(first);
    }
    static java.util.stream.Stream<FrozenKnowledgeCases.Scenario> frozenRegistrationCases() throws Exception {
        return FrozenKnowledgeCases.loadIds(java.util.Set.of("LC1","LC2")).stream();
    }

    @Test void updateCreatesNewSourceVersionButRetainsOldActiveVersion() throws Exception {
        var db = new Simulation();
        Receipt first = db.adapter().register(request(), LEXICAL);
        db.documents.get("public-guide").put("active_version", 1L);
        Receipt second = db.adapter().register(request("import-request-key-0002", "新的公开说明"), LEXICAL);
        assertThat(second.documentId()).isEqualTo(first.documentId());
        assertThat(second.documentVersion()).isEqualTo(2);
        assertThat(db.documents.get("public-guide").get("active_version")).isEqualTo(1L);
        assertThat(db.originals.get(first.documentId() + ":1")).isEqualTo(request().text());
        assertThat(db.originals.get(first.documentId() + ":2")).isEqualTo("新的公开说明");
    }

    @Test void sameKeyReturnsOriginalJobWithoutNewDocumentOrVersion() throws Exception {
        var db = new Simulation();
        var first = db.adapter().register(request(), LEXICAL);
        int writes = db.writes;
        assertThat(db.adapter().register(request(), LEXICAL)).isEqualTo(first);
        assertThat(db.writes).isEqualTo(writes);
        assertThat(db.jobs).hasSize(1);
    }

    @Test void fullPayloadAndProfileAreBoundToIdempotencyKey() throws Exception {
        var db = new Simulation();
        db.adapter().register(request(), LEXICAL);
        for (var changed : List.of(
                request(request().idempotencyKey(), "别的原文"),
                new KnowledgeImportRequest("other", "操作说明", request().text(), "zh-CN", 1, 10, request().idempotencyKey()),
                new KnowledgeImportRequest("public-guide", "另一标题", request().text(), "zh-CN", 1, 10, request().idempotencyKey()),
                new KnowledgeImportRequest("public-guide", "操作说明", request().text(), "en", 1, 10, request().idempotencyKey()),
                new KnowledgeImportRequest("public-guide", "操作说明", request().text(), "zh-CN", 2, 10, request().idempotencyKey()))) {
            assertThatThrownBy(() -> db.adapter().register(changed, LEXICAL))
                    .isInstanceOf(KnowledgeImportException.class).hasMessage("IDEMPOTENCY_CONFLICT");
        }
        for (var changed : List.of(new BuildSpecification(Optional.empty(), "t2", "c1"),
                new BuildSpecification(Optional.empty(), "t1", "c2"),
                new BuildSpecification(Optional.of(new EmbeddingProfile("p1", 2)), "t1", "c1"))) {
            assertThatThrownBy(() -> db.adapter().register(request(), changed)).hasMessage("IDEMPOTENCY_CONFLICT");
        }
        assertThat(db.jobs).hasSize(1);
    }

    @Test void twentyConcurrentSameKeyRequestsAcrossInstancesRegisterOnlyOnce() throws Exception {
        var db = new Simulation();
        var a = db.adapter();
        var b = db.adapter();
        var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Callable<Receipt>>();
            for (int i = 0; i < 20; i++) {
                int index = i;
                tasks.add(() -> (index % 2 == 0 ? a : b).register(request(), LEXICAL));
            }
            var results = pool.invokeAll(tasks, 5, TimeUnit.SECONDS);
            var ids = new java.util.HashSet<UUID>();
            for (var result : results) { ids.add(result.get().id()); }
            assertThat(ids).hasSize(1);
            assertThat(db.jobs).hasSize(1);
            assertThat(db.documents).hasSize(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void deletedDocumentCannotBeResurrectedByReplayNewKeyOrStatusQuery() throws Exception {
        var db = new Simulation();
        var first = db.adapter().register(request(), LEXICAL);
        db.documents.get("public-guide").put("status", "DELETED");
        assertThatThrownBy(() -> db.adapter().register(request(), LEXICAL)).hasMessage("SOURCE_DELETED");
        assertThatThrownBy(() -> db.adapter().register(request("import-request-key-0002", "正文"), LEXICAL)).hasMessage("SOURCE_DELETED");
        assertThatThrownBy(() -> db.adapter().find(first.id())).hasMessage("SOURCE_DELETED");
        assertThat(db.jobs).hasSize(1);
    }

    @Test void documentAndPendingLimitsRejectBeforeInsert() throws Exception {
        var db = new Simulation();
        db.documentCount = 100L;
        assertThatThrownBy(() -> db.adapter().register(request(), LEXICAL)).hasMessage("RESOURCE_LIMIT");
        assertThat(db.writes).isZero();
        db.documentCount = null;
        db.pendingCount = 100L;
        assertThatThrownBy(() -> db.adapter().register(request(), LEXICAL)).hasMessage("RESOURCE_LIMIT");
        assertThat(db.writes).isZero();
    }

    @Test void partialRegistrationFailureRollsBackAllNewMaterial() throws Exception {
        var db = new Simulation();
        db.failAtWrite = 2;
        assertThatThrownBy(() -> db.adapter().register(request(), LEXICAL)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(db.documents).isEmpty();
        assertThat(db.jobs).isEmpty();
        assertThat(db.originals).isEmpty();
        assertThat(db.controlVersion).isEqualTo(1);
    }

    @Test void commitResponseLostIsRecoveredBySameKeyWithoutDuplicateVersion() throws Exception {
        var db = new Simulation();
        db.loseCommitResponse = true;
        assertThatThrownBy(() -> db.adapter().register(request(), LEXICAL)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(db.jobs).hasSize(1);
        int writes = db.writes;
        db.loseCommitResponse = false;
        var recovered = db.adapter().register(request(), LEXICAL);
        assertThat(recovered.id().toString()).isEqualTo(db.jobs.values().iterator().next().get("id"));
        assertThat(db.writes).isEqualTo(writes);
    }

    @Test void findMissingIsEmptyButStorageFailureIsNotEmpty() throws Exception {
        var db = new Simulation();
        assertThat(db.adapter().find(UUID.randomUUID())).isEmpty();
        db.failReads = true;
        assertThatThrownBy(() -> db.adapter().find(UUID.randomUUID())).isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test void invalidKeyUnicodeAndByteLimitFailBeforeDatabaseUse() {
        assertThatIllegalArgumentException().isThrownBy(() -> request("short", "公开"));
        assertThatIllegalArgumentException().isThrownBy(() -> request("import key contains spaces", "公开"));
        assertThatIllegalArgumentException().isThrownBy(() -> request("import-request-key-0001", "\uD800"));
        assertThatIllegalArgumentException().isThrownBy(() -> request("import-request-key-0001", "中".repeat(87382)));
        assertThatIllegalArgumentException().isThrownBy(() -> new KnowledgeImportRequest("../guide", "标题", "公开", "zh-CN", 1, 2, "import-request-key-0001"));
        assertThatIllegalArgumentException().isThrownBy(() -> new KnowledgeImportRequest("guide", "标题", "公开", "zh-CN", 2, 1, "import-request-key-0001"));
    }

    private static final class Simulation {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        final ReentrantLock lock = new ReentrantLock();
        final Map<String, Map<String, Object>> documents = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> jobs = new LinkedHashMap<>();
        final Map<String, Long> versions = new HashMap<>();
        final Map<String, String> originals = new HashMap<>();
        final List<String> sql = new ArrayList<>();
        Map<String, Map<String, Object>> oldDocuments;
        Map<String, Map<String, Object>> oldJobs;
        Map<String, Long> oldVersions;
        Map<String, String> oldOriginals;
        long controlVersion = 1, oldControl;
        Long documentCount, pendingCount;
        int writes, currentWrites, failAtWrite;
        boolean loseCommitResponse, failReads;

        @SuppressWarnings({"rawtypes", "unchecked"})
        Simulation() throws Exception {
            when(transactions.getTransaction(any())).thenAnswer(call -> {
                lock.lock();
                oldDocuments = copy(documents); oldJobs = copy(jobs);
                oldVersions = new HashMap<>(versions); oldOriginals = new HashMap<>(originals);
                oldControl = controlVersion; currentWrites = 0;
                return new SimpleTransactionStatus();
            });
            doAnswer(call -> {
                lock.unlock();
                if (loseCommitResponse) { throw new DataAccessResourceFailureException("simulated commit response lost"); }
                return null;
            }).when(transactions).commit(any());
            doAnswer(call -> {
                documents.clear(); documents.putAll(oldDocuments); jobs.clear(); jobs.putAll(oldJobs);
                versions.clear(); versions.putAll(oldVersions); originals.clear(); originals.putAll(oldOriginals);
                controlVersion = oldControl;
                lock.unlock(); return null;
            }).when(transactions).rollback(any());
            doAnswer(call -> {
                checkRead();
                RowMapper mapper = call.getArgument(1);
                return List.of(mapper.mapRow(row(Map.of("version", controlVersion)), 0));
            }).when(jdbc).query(anyString(), any(RowMapper.class));
            doAnswer(call -> {
                checkRead();
                String query = call.getArgument(0);
                RowMapper mapper = call.getArgument(1);
                Object argument = call.getArguments()[2];
                Map<String, Object> value;
                if (query.contains("FROM knowledge_import_job j")) {
                    if (query.contains("idempotency_key_hash")) {
                        value = jobs.get(java.util.HexFormat.of().formatHex((byte[]) argument));
                    } else {
                        value = jobs.values().stream().filter(j -> j.get("id").equals(argument)).findFirst().orElse(null);
                    }
                    if (value != null) {
                        value = new HashMap<>(value);
                        String documentId = (String) value.get("document_id");
                        value.put("document_status", documents.values().stream()
                                .filter(d -> d.get("id").equals(documentId)).findFirst().orElseThrow().get("status"));
                    }
                } else {
                    value = documents.get(argument);
                }
                return value == null ? List.of() : List.of(mapper.mapRow(row(value), 0));
            }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
            when(jdbc.queryForObject(anyString(), eq(Long.class))).thenAnswer(call -> {
                checkRead();
                String query = call.getArgument(0);
                return query.contains("knowledge_import_job") ? (pendingCount == null ? (long) jobs.size() : pendingCount)
                        : (documentCount == null ? (long) documents.size() : documentCount);
            });
            when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenAnswer(call -> {
                checkRead(); return versions.getOrDefault(call.getArguments()[2], 0L);
            });
            when(jdbc.queryForObject(eq("SELECT UTC_TIMESTAMP(3)"), eq(Timestamp.class)))
                    .thenReturn(Timestamp.from(Instant.parse("2026-09-09T00:00:00Z")));
            doAnswer(call -> {
                String query = call.getArgument(0);
                Object[] args = java.util.Arrays.copyOfRange(call.getArguments(), 1, call.getArguments().length);
                assertThat(query.chars().filter(ch -> ch == '?').count()).isEqualTo(args.length);
                sql.add(query);
                currentWrites++;
                if (failAtWrite == currentWrites) { throw new DataAccessResourceFailureException("simulated write failed"); }
                writes++;
                if (query.startsWith("INSERT INTO knowledge_document (")) {
                    var doc = new HashMap<String, Object>();
                    doc.put("id", args[0]); doc.put("status", "ACTIVE"); doc.put("row_version", 1L);
                    documents.put((String) args[1], doc);
                } else if (query.startsWith("UPDATE knowledge_document SET")) {
                    assertThat(query).doesNotContain("active_version");
                    var doc = documents.values().stream().filter(d -> d.get("id").equals(args[1])).findFirst().orElseThrow();
                    if (!doc.get("row_version").equals(args[2])) { return 0; }
                    doc.put("row_version", ((Long) doc.get("row_version")) + 1);
                } else if (query.startsWith("INSERT INTO knowledge_document_version")) {
                    versions.put((String) args[0], (Long) args[1]);
                    originals.put(args[0] + ":" + args[1], (String) args[6]);
                } else if (query.startsWith("INSERT INTO knowledge_import_job")) {
                    var receipt = new HashMap<String, Object>();
                    receipt.put("id", args[0]); receipt.put("document_id", args[1]); receipt.put("document_version", args[2]);
                    receipt.put("request_digest", args[4]); receipt.put("status", "PENDING"); receipt.put("attempts", 0);
                    receipt.put("error_code", "NONE");
                    jobs.put(java.util.HexFormat.of().formatHex((byte[]) args[3]), receipt);
                } else if (query.contains("UPDATE knowledge_index_control")) {
                    if (!Long.valueOf(controlVersion).equals(args[0])) { return 0; }
                    controlVersion++;
                } else { throw new AssertionError("Unexpected SQL"); }
                return 1;
            }).when(jdbc).update(anyString(), any(Object[].class));
        }

        JdbcKnowledgeImportRegistrationAdapter adapter() {
            return new JdbcKnowledgeImportRegistrationAdapter(jdbc, transactions, Duration.ofMinutes(10), 100);
        }
        void checkRead() { if (failReads) { throw new DataAccessResourceFailureException("simulated storage unavailable"); } }
        Map<String, Map<String, Object>> copy(Map<String, Map<String, Object>> source) {
            var result = new LinkedHashMap<String, Map<String, Object>>();
            source.forEach((key, value) -> result.put(key, new HashMap<>(value))); return result;
        }
        ResultSet row(Map<String, Object> values) throws Exception {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
            when(rs.getBytes(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
            when(rs.getLong(anyString())).thenAnswer(call -> ((Number) values.getOrDefault(call.getArgument(0), 0L)).longValue());
            when(rs.getInt(anyString())).thenAnswer(call -> ((Number) values.getOrDefault(call.getArgument(0), 0)).intValue());
            return rs;
        }
    }
}
