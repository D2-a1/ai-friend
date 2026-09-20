package com.aifriend.retrieval.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifriend.retrieval.application.KnowledgeGenerationPort;
import com.aifriend.retrieval.application.KnowledgeRepositoryPort.Snapshot;
import com.aifriend.retrieval.domain.KnowledgeChunk;

/**
 * 分批暂存完整世代，发布事务同时切换来源、索引指针与任务状态。
 * 不调用模型；不自动重试结果未知的提交；未默认装配为Bean。
 * @author codex
 * @since 1.0.0
 */
public final class JdbcKnowledgeGenerationAdapter implements KnowledgeGenerationPort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final JdbcKnowledgeBuildSourceAdapter sources;
    private final KnowledgeSnapshotDigest digests = new KnowledgeSnapshotDigest();
    private final KnowledgeVectorCodec codec = new KnowledgeVectorCodec();
    private static final String CHUNK_COLUMNS = """
            SELECT BIN_TO_UUID(k.id) chunk_id, BIN_TO_UUID(k.document_id) document_id,
                   k.document_version, k.ordinal, k.heading, k.chunk_text, k.source_start,
                   k.source_end, k.content_digest, k.chunker_version
            FROM knowledge_chunk k
            """;

    /**
     * 每批使用独立短事务；与来源读取共用同数据源的事务连接。
     * @param source 受控数据源
     * @param transactions 对应数据源事务管理器
     */
    public JdbcKnowledgeGenerationAdapter(DataSource source, PlatformTransactionManager transactions) {
        this(new JdbcTemplate(Objects.requireNonNull(source, "source")), transactions);
        jdbc.setQueryTimeout(2);
        jdbc.setFetchSize(128);
    }

    JdbcKnowledgeGenerationAdapter(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this(jdbc, transactions, new JdbcKnowledgeBuildSourceAdapter(jdbc, transactions));
    }

    JdbcKnowledgeGenerationAdapter(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            JdbcKnowledgeBuildSourceAdapter sources) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.sources = Objects.requireNonNull(sources, "sources");
        transaction = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(2);
    }

    /** {@inheritDoc} */
    @Override public void begin(Build build) {
        Objects.requireNonNull(build, "build");
        byte[] digest = digests.calculate(build.snapshot());
        transaction.executeWithoutResult(status -> {
            validateSources(build);
            if (generation(build, digest, false)) { return; }
            // 控制行已锁且当前租约有效，仅回收非活动、非本次世代，保留当前可读世代。
            var stale = jdbc.query("""
                    SELECT BIN_TO_UUID(g.id) id FROM knowledge_index_generation g
                    JOIN knowledge_index_control c ON c.singleton_id=1
                    WHERE c.active_generation_id IS NULL OR g.id<>c.active_generation_id
                    ORDER BY g.id LIMIT 3
                    """, (rs, row) -> UUID.fromString(rs.getString("id")));
            if (stale.size() > 2) { throw invalid("GENERATION_LIMIT"); }
            for (UUID id : stale) {
                jdbc.update("DELETE FROM knowledge_embedding WHERE generation_id=UUID_TO_BIN(?)", id.toString());
                jdbc.update("DELETE FROM knowledge_generation_chunk WHERE generation_id=UUID_TO_BIN(?)", id.toString());
                requireOne(jdbc.update("DELETE FROM knowledge_index_generation WHERE id=UUID_TO_BIN(?)", id.toString()));
            }
            var version = build.snapshot().version();
            var claim = build.source().claim();
            requireOne(jdbc.update("""
                    INSERT INTO knowledge_index_generation
                    (id, build_job_id, build_lease_token, corpus_revision, profile_id, dimension,
                     tokenizer_version, chunker_version, corpus_digest, document_count, chunk_count, status, created_at)
                    VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, ?, ?, ?, ?, 'BUILDING', UTC_TIMESTAMP(3))
                    """, version.generation().toString(), claim.job().id().toString(),
                    claim.job().leaseToken().orElseThrow().toString(), version.corpusRevision(),
                    version.embeddingProfile().map(p -> p.id()).orElse(null),
                    version.embeddingProfile().map(p -> p.dimension()).orElse(null),
                    version.tokenizerVersion(), version.chunkerVersion(), digest,
                    build.snapshot().documents().size(), build.snapshot().chunks().size()));
            ensureTime(build);
        });
    }

    /** {@inheritDoc} */
    @Override public void stage(Build build, List<UUID> chunkIds, Map<UUID, float[]> vectors) {
        Objects.requireNonNull(build, "build");
        var ids = List.copyOf(chunkIds);
        if (ids.isEmpty() || ids.size() > 64 || new HashSet<>(ids).size() != ids.size()) { throw invalid("INVALID_STAGE_BATCH"); }
        var expected = new HashMap<UUID, KnowledgeChunk>();
        build.snapshot().chunks().forEach(chunk -> expected.put(chunk.id(), chunk));
        if (!expected.keySet().containsAll(ids)) { throw invalid("FOREIGN_STAGE_CHUNK"); }
        var profile = build.snapshot().version().embeddingProfile();
        var input = Map.copyOf(vectors);
        if (profile.isPresent() ? !input.keySet().equals(new HashSet<>(ids)) : !input.isEmpty()) {
            throw invalid("INVALID_STAGE_VECTORS");
        }
        var encoded = new HashMap<UUID, KnowledgeVectorCodec.Stored>();
        input.forEach((id, vector) -> encoded.put(id, codec.encode(profile.orElseThrow(),
                build.snapshot().version().generation(), id, vector)));
        byte[] digest = digests.calculate(build.snapshot());
        transaction.executeWithoutResult(status -> {
            validateSources(build);
            generation(build, digest, true);
            var rows = new ArrayList<Object[]>();
            for (UUID id : ids) {
                var chunk = expected.get(id);
                rows.add(new Object[] {id.toString(), chunk.documentId().toString(), chunk.documentVersion(),
                        chunk.ordinal(), chunk.chunkerVersion(), chunk.heading(), chunk.text(),
                        chunk.sourceStart(), chunk.sourceEnd(), sha(chunk.text())});
            }
            jdbc.batchUpdate("""
                    INSERT INTO knowledge_chunk
                    (id, document_id, document_version, ordinal, chunker_version, heading, chunk_text, source_start, source_end, content_digest)
                    VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE id=knowledge_chunk.id
                    """, rows);
            // 唯一约束冲突不能悄悄忽略，逐字段回读确认不可变片段相同。
            var stored = jdbc.query(CHUNK_COLUMNS + " WHERE k.id IN (" + placeholders(ids.size()) + ")",
                    this::chunk, ids.stream().map(UUID::toString).toArray());
            if (stored.size() != ids.size() || stored.stream().anyMatch(c -> !c.equals(expected.get(c.id())))
                    || stored.stream().map(KnowledgeChunk::id).distinct().count() != ids.size()) {
                throw invalid("STAGED_CHUNK_CONFLICT");
            }
            String gen = build.snapshot().version().generation().toString();
            jdbc.batchUpdate("""
                    INSERT INTO knowledge_generation_chunk (generation_id, chunk_id)
                    VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?)) ON DUPLICATE KEY UPDATE chunk_id=knowledge_generation_chunk.chunk_id
                    """, ids.stream().map(id -> new Object[] {gen, id.toString()}).toList());
            if (profile.isPresent()) {
                jdbc.batchUpdate("""
                        INSERT INTO knowledge_embedding (generation_id, chunk_id, dimension, vector_blob, digest)
                        VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?)
                        ON DUPLICATE KEY UPDATE digest=knowledge_embedding.digest
                        """, ids.stream().map(id -> new Object[] {gen, id.toString(), profile.orElseThrow().dimension(),
                                encoded.get(id).bytes(), encoded.get(id).digest()}).toList());
                var args = new ArrayList<Object>();
                args.add(gen);
                ids.forEach(id -> args.add(id.toString()));
                var saved = jdbc.query("""
                        SELECT BIN_TO_UUID(chunk_id) chunk_id, dimension, vector_blob, digest
                        FROM knowledge_embedding WHERE generation_id=UUID_TO_BIN(?) AND chunk_id IN (
                        """ + placeholders(ids.size()) + ")", (rs, row) -> {
                            UUID id = UUID.fromString(rs.getString("chunk_id"));
                            var wanted = encoded.get(id);
                            if (wanted == null || rs.getInt("dimension") != profile.orElseThrow().dimension()
                                    || !Arrays.equals(wanted.bytes(), rs.getBytes("vector_blob"))
                                    || !Arrays.equals(wanted.digest(), rs.getBytes("digest"))) {
                                throw invalid("STAGED_VECTOR_CONFLICT");
                            }
                            return id;
                        }, args.toArray());
                if (saved.size() != ids.size() || new HashSet<>(saved).size() != ids.size()) { throw invalid("INCOMPLETE_STAGE"); }
            }
            ensureTime(build);
        });
    }

    /** {@inheritDoc} */
    @Override public void publish(Build build) {
        Objects.requireNonNull(build, "build");
        byte[] digest = digests.calculate(build.snapshot());
        transaction.executeWithoutResult(status -> {
            validateSources(build);
            generation(build, digest, true);
            String gen = build.snapshot().version().generation().toString();
            var stored = jdbc.query(CHUNK_COLUMNS + """
                    JOIN knowledge_generation_chunk m ON m.chunk_id=k.id
                    WHERE m.generation_id=UUID_TO_BIN(?) ORDER BY k.id LIMIT 2001
                    """, this::chunk, gen);
            var reconstructed = new Snapshot(build.snapshot().version(), build.source().documents(), stored);
            if (!MessageDigest.isEqual(digest, digests.calculate(reconstructed))) { throw invalid("INCOMPLETE_GENERATION"); }
            validateVectors(build, stored);
            var claim = build.source().claim();
            String doc = build.source().targetDocument().toString();
            long target = build.source().targetVersion();
            requireOne(jdbc.update("""
                    UPDATE knowledge_document_version SET status='READY'
                    WHERE document_id=UUID_TO_BIN(?) AND version=? AND status IN ('BUILDING', 'READY')
                    """, doc, target));
            requireOne(jdbc.update("""
                    UPDATE knowledge_document SET active_version=?, row_version=row_version+1, updated_at=UTC_TIMESTAMP(3)
                    WHERE id=UUID_TO_BIN(?) AND status='ACTIVE' AND deleted_at IS NULL
                      AND (active_version IS NULL OR active_version<?)
                    """, target, doc, target));
            jdbc.update("""
                    UPDATE knowledge_document_version SET status='RETIRED'
                    WHERE document_id=UUID_TO_BIN(?) AND version<>? AND status='READY'
                    """, doc, target);
            jdbc.update("""
                    UPDATE knowledge_index_generation SET status='RETIRED'
                    WHERE id=(SELECT active_generation_id FROM knowledge_index_control WHERE singleton_id=1)
                      AND status='ACTIVE'
                    """);
            requireOne(jdbc.update("""
                    UPDATE knowledge_index_generation SET status='ACTIVE'
                    WHERE id=UUID_TO_BIN(?) AND status='BUILDING' AND build_job_id=UUID_TO_BIN(?) AND build_lease_token=UUID_TO_BIN(?)
                    """, gen, claim.job().id().toString(), claim.job().leaseToken().orElseThrow().toString()));
            // 最后的CAS使用数据库当前时间：长读取或锁等待跨过租约后，整个事务回滚。
            requireOne(jdbc.update("""
                    UPDATE knowledge_index_control SET active_generation_id=UUID_TO_BIN(?), version=version+1,
                           lease_job_id=NULL, lease_token=NULL, lease_until=NULL
                    WHERE singleton_id=1 AND version=? AND corpus_revision=? AND lease_job_id=UUID_TO_BIN(?)
                      AND lease_token=UUID_TO_BIN(?) AND lease_until>UTC_TIMESTAMP(3)
                    """, gen, claim.controlVersion(), claim.corpusRevision(), claim.job().id().toString(),
                    claim.job().leaseToken().orElseThrow().toString()));
            requireOne(jdbc.update("""
                    UPDATE knowledge_import_job SET status='READY', version=version+1, lease_token=NULL, lease_until=NULL,
                           error_code='NONE', updated_at=UTC_TIMESTAMP(3)
                    WHERE id=UUID_TO_BIN(?) AND status='PROCESSING' AND version=? AND lease_token=UUID_TO_BIN(?)
                      AND lease_until>UTC_TIMESTAMP(3) AND deadline>UTC_TIMESTAMP(3)
                    """, claim.job().id().toString(), claim.job().version(), claim.job().leaseToken().orElseThrow().toString()));
        });
    }

    private void validateSources(Build build) {
        var current = sources.loadLocked(build.source().claim());
        if (!current.specification().equals(build.source().specification())
                || !current.targetDocument().equals(build.source().targetDocument())
                || current.targetVersion() != build.source().targetVersion()
                || !new HashSet<>(current.documents()).equals(new HashSet<>(build.source().documents()))) {
            throw new ConcurrencyFailureException("BUILD_SOURCE_CHANGED");
        }
    }

    private boolean generation(Build build, byte[] digest, boolean required) {
        var rows = jdbc.query("""
                SELECT BIN_TO_UUID(build_job_id) build_job_id, BIN_TO_UUID(build_lease_token) build_lease_token,
                       corpus_revision, profile_id, dimension, tokenizer_version, chunker_version,
                       corpus_digest, document_count, chunk_count, status
                FROM knowledge_index_generation WHERE id=UUID_TO_BIN(?) FOR UPDATE
                """, (rs, row) -> {
                    var version = build.snapshot().version();
                    var claim = build.source().claim();
                    boolean valid = claim.job().id().toString().equals(rs.getString("build_job_id"))
                            && claim.job().leaseToken().orElseThrow().toString().equals(rs.getString("build_lease_token"))
                            && version.corpusRevision() == rs.getLong("corpus_revision")
                            && Objects.equals(version.embeddingProfile().map(p -> p.id()).orElse(null), rs.getString("profile_id"))
                            && Objects.equals(version.embeddingProfile().map(p -> p.dimension()).orElse(null), rs.getObject("dimension"))
                            && version.tokenizerVersion().equals(rs.getString("tokenizer_version"))
                            && version.chunkerVersion().equals(rs.getString("chunker_version"))
                            && MessageDigest.isEqual(digest, Objects.requireNonNullElseGet(rs.getBytes("corpus_digest"), () -> new byte[0]))
                            && build.snapshot().documents().size() == rs.getInt("document_count")
                            && build.snapshot().chunks().size() == rs.getInt("chunk_count")
                            && "BUILDING".equals(rs.getString("status"));
                    if (!valid) { throw invalid("GENERATION_CONFLICT"); }
                    return true;
                }, build.snapshot().version().generation().toString());
        if (rows.size() > 1 || (required && rows.isEmpty())) { throw invalid("GENERATION_MISSING"); }
        return !rows.isEmpty();
    }

    private void validateVectors(Build build, List<KnowledgeChunk> chunks) {
        String gen = build.snapshot().version().generation().toString();
        var profile = build.snapshot().version().embeddingProfile();
        if (profile.isEmpty()) {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_embedding WHERE generation_id=UUID_TO_BIN(?)", Long.class, gen);
            if (count == null || count != 0) { throw invalid("UNEXPECTED_VECTORS"); }
            return;
        }
        var ids = jdbc.query("""
                SELECT BIN_TO_UUID(m.chunk_id) chunk_id, e.dimension, e.vector_blob, e.digest
                FROM knowledge_generation_chunk m LEFT JOIN knowledge_embedding e
                  ON e.generation_id=m.generation_id AND e.chunk_id=m.chunk_id
                WHERE m.generation_id=UUID_TO_BIN(?) LIMIT 2001
                """, (rs, row) -> {
                    UUID id = UUID.fromString(rs.getString("chunk_id"));
                    if (rs.getInt("dimension") != profile.orElseThrow().dimension()) { throw invalid("INCOMPLETE_VECTORS"); }
                    codec.decode(profile.orElseThrow(), build.snapshot().version().generation(), id,
                            new KnowledgeVectorCodec.Stored(rs.getBytes("vector_blob"), rs.getBytes("digest")));
                    return id;
                }, gen);
        var expected = chunks.stream().map(KnowledgeChunk::id).collect(java.util.stream.Collectors.toSet());
        if (ids.size() != expected.size() || !new HashSet<>(ids).equals(expected)) { throw invalid("INCOMPLETE_VECTORS"); }
    }

    private KnowledgeChunk chunk(ResultSet rs, int row) throws SQLException {
        String text = rs.getString("chunk_text");
        byte[] expected = rs.getBytes("content_digest");
        if (text == null || expected == null || !MessageDigest.isEqual(sha(text), expected)) { throw invalid("CORRUPT_CHUNK"); }
        return new KnowledgeChunk(UUID.fromString(rs.getString("chunk_id")), UUID.fromString(rs.getString("document_id")),
                rs.getLong("document_version"), rs.getInt("ordinal"), rs.getString("heading"), text,
                rs.getInt("source_start"), rs.getInt("source_end"), rs.getString("chunker_version"));
    }

    private void ensureTime(Build build) {
        var now = jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", java.sql.Timestamp.class);
        if (now == null || !now.toInstant().isBefore(build.source().claim().job().leaseUntil().orElseThrow())
                || !now.toInstant().isBefore(build.source().claim().job().deadline())) {
            throw new ConcurrencyFailureException("IMPORT_LEASE_LOST");
        }
    }

    private static String placeholders(int count) { return String.join(",", java.util.Collections.nCopies(count, "UUID_TO_BIN(?)")); }
    private static void requireOne(int count) {
        if (count != 1) { throw new ConcurrencyFailureException("GENERATION_STATE_CONFLICT"); }
    }
    private static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }
    private static byte[] sha(String text) {
        try { return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
    }
}
