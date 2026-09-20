package com.aifriend.retrieval.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifriend.retrieval.application.KnowledgeRepositoryPort;
import com.aifriend.retrieval.application.KnowledgeEvidencePort;
import com.aifriend.retrieval.application.KnowledgeRetrievalException;
import com.aifriend.retrieval.application.KnowledgeRetrievalException.Kind;
import com.aifriend.retrieval.application.KnowledgeVectorRepositoryPort;
import com.aifriend.retrieval.domain.EmbeddingProfile;
import com.aifriend.retrieval.domain.IndexVersion;
import com.aifriend.retrieval.domain.KnowledgeChunk;
import com.aifriend.retrieval.domain.KnowledgeDocument;

/**
 * 公开索引权威读取适配器。每次读取/返回前复验都开启新的只读一致性事务。
 * 不使用长期缓存，不在事务中调用模型，不把数据库故障映射为无证据。
 * 未作为默认Bean装配；后续受功能开关控制。
 * @author codex
 * @since 1.0.0
 */
public final class JdbcKnowledgeAdapter implements KnowledgeEvidencePort, KnowledgeVectorRepositoryPort {
    private static final String HEADER = """
            SELECT BIN_TO_UUID(c.active_generation_id) active_id,
                   BIN_TO_UUID(g.id) generation_id, g.corpus_revision, g.profile_id, g.dimension,
                   g.tokenizer_version, g.chunker_version, g.corpus_digest,
                   g.document_count, g.chunk_count, g.status
            FROM knowledge_index_control c
            LEFT JOIN knowledge_index_generation g ON g.id = c.active_generation_id
            WHERE c.singleton_id = 1
            """;
    private static final String DOCUMENTS = """
            SELECT DISTINCT BIN_TO_UUID(d.id) document_id, d.source_key, d.status document_status,
                   d.active_version, d.deleted_at, v.version, v.title, v.locale,
                   v.min_app_version, v.max_app_version, v.original_text, v.content_digest,
                   v.chunker_version, v.status version_status
            FROM knowledge_generation_chunk m
            JOIN knowledge_chunk k ON k.id = m.chunk_id
            JOIN knowledge_document d ON d.id = k.document_id
            JOIN knowledge_document_version v ON v.document_id = k.document_id AND v.version = k.document_version
            WHERE m.generation_id = UUID_TO_BIN(?)
            ORDER BY document_id, version LIMIT 101
            """;
    private static final String CHUNKS = """
            SELECT BIN_TO_UUID(k.id) chunk_id, BIN_TO_UUID(k.document_id) document_id,
                   k.document_version, k.ordinal, k.heading, k.chunk_text,
                   k.source_start, k.source_end, k.content_digest, k.chunker_version
            FROM knowledge_generation_chunk m
            JOIN knowledge_chunk k ON k.id = m.chunk_id
            JOIN knowledge_document_version v ON v.document_id = k.document_id AND v.version = k.document_version
            WHERE m.generation_id = UUID_TO_BIN(?)
            ORDER BY chunk_id LIMIT 2001
            """;
    private static final String VECTORS = """
            SELECT BIN_TO_UUID(m.chunk_id) chunk_id, e.dimension, e.vector_blob, e.digest
            FROM knowledge_generation_chunk m
            LEFT JOIN knowledge_embedding e ON e.generation_id = m.generation_id AND e.chunk_id = m.chunk_id
            WHERE m.generation_id = UUID_TO_BIN(?)
            ORDER BY chunk_id LIMIT 2001
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate readTransaction;
    private final KnowledgeSnapshotDigest snapshotDigest = new KnowledgeSnapshotDigest();
    private final KnowledgeVectorCodec vectorCodec = new KnowledgeVectorCodec();

    /**
     * 使用独立JdbcTemplate限制SQL等待，不改动旧任务链共用模板。
     * @param source 既有受控数据源
     * @param transactions 对应数据源事务管理器
     */
    public JdbcKnowledgeAdapter(DataSource source, PlatformTransactionManager transactions) {
        this(new JdbcTemplate(Objects.requireNonNull(source, "source")), transactions);
        jdbc.setQueryTimeout(2);
        jdbc.setFetchSize(128);
    }

    // 模拟结果集测试使用此构造器，不连接本机MySQL。
    JdbcKnowledgeAdapter(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        readTransaction = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
        readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        readTransaction.setReadOnly(true);
        readTransaction.setTimeout(2);
    }

    /** {@inheritDoc} */
    @Override public Optional<Snapshot> readActive() {
        return readTransaction.execute(status -> validatedSnapshot());
    }

    /** {@inheritDoc} */
    @Override public boolean isCurrent(IndexVersion version, List<KnowledgeChunk> evidence) {
        Objects.requireNonNull(version, "version");
        List<KnowledgeChunk> checked = List.copyOf(evidence);
        if (checked.size() > 4) { throw new IllegalArgumentException("INVALID_EVIDENCE_LIMIT"); }
        try {
            return Boolean.TRUE.equals(readTransaction.execute(status -> {
                var active = validatedSnapshot();
                if (active.isEmpty() || !active.orElseThrow().version().equals(version)) { return false; }
                var chunks = active.orElseThrow().chunks();
                return checked.stream().allMatch(chunks::contains);
            }));
        } catch (KnowledgeRetrievalException changed) {
            if (changed.kind() == Kind.SOURCE_CHANGED) { return false; }
            throw changed;
        }
    }

    /** {@inheritDoc} */
    @Override public boolean isCurrentForScope(IndexVersion version, List<KnowledgeChunk> evidence,
            String locale, int appVersion) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(locale, "locale");
        List<KnowledgeChunk> checked=List.copyOf(evidence);
        if (checked.size()>4 || appVersion<1) throw new IllegalArgumentException("INVALID_EVIDENCE_SCOPE");
        try {
            return Boolean.TRUE.equals(readTransaction.execute(status -> {
                var active=validatedSnapshot();
                if (active.isEmpty() || !active.orElseThrow().version().equals(version)) return false;
                var snapshot=active.orElseThrow();
                return checked.stream().allMatch(chunk -> snapshot.chunks().contains(chunk)
                        && snapshot.documents().stream().anyMatch(document ->
                            document.id().equals(chunk.documentId()) && document.version()==chunk.documentVersion()
                            && document.appliesTo(locale,appVersion)));
            }));
        } catch (KnowledgeRetrievalException changed) {
            if (changed.kind()==Kind.SOURCE_CHANGED) return false;
            throw changed;
        }
    }

    /** {@inheritDoc} */
    @Override public Map<UUID, float[]> load(IndexVersion version) {
        Objects.requireNonNull(version, "version");
        return readTransaction.execute(status -> {
            var active = validatedSnapshot().orElseThrow(() -> new KnowledgeRetrievalException(Kind.NO_INDEX));
            if (!active.version().equals(version)) { throw new KnowledgeRetrievalException(Kind.SOURCE_CHANGED); }
            return vectorsInTransaction(active);
        });
    }

    /** 仅同包删除重建在受控事务内复用；不打开另一个连接或允许跳过向量完整性。 */
    Map<UUID,float[]> vectorsInTransaction(Snapshot active) {
            var version=active.version();
            var profile = version.embeddingProfile().orElseThrow(() -> new KnowledgeRetrievalException(Kind.INDEX_INVALID));
            try {
                List<VectorRow> rows = jdbc.query(VECTORS, (rs, row) -> {
                    UUID chunk = UUID.fromString(rs.getString("chunk_id"));
                    Integer dimension = (Integer) rs.getObject("dimension");
                    if (dimension == null || dimension != profile.dimension()) {
                        throw new IllegalArgumentException("INVALID_VECTOR_DIMENSION");
                    }
                    return new VectorRow(chunk, vectorCodec.decode(profile, version.generation(), chunk,
                            new KnowledgeVectorCodec.Stored(rs.getBytes("vector_blob"), rs.getBytes("digest"))));
                }, version.generation().toString());
                if (rows.size() != active.chunks().size()) { throw new IllegalArgumentException("INCOMPLETE_VECTORS"); }
                var expected = active.chunks().stream().map(KnowledgeChunk::id).collect(java.util.stream.Collectors.toSet());
                var vectors = new HashMap<UUID, float[]>();
                for (var row : rows) {
                    if (!expected.contains(row.id()) || vectors.put(row.id(), row.vector()) != null) {
                        throw new IllegalArgumentException("INVALID_VECTOR_MANIFEST");
                    }
                }
                return Map.copyOf(vectors);
            } catch (IllegalArgumentException invalid) {
                throw new KnowledgeRetrievalException(Kind.INDEX_INVALID);
            }
    }

    private Optional<Snapshot> validatedSnapshot() {
        return validatedSnapshot(null);
    }

    /** 删除派生世代专用，只有已删除来源允许读取旧READY原文；普通查询始终拒绝。 */
    Optional<DeletionSource> deletionSourceInTransaction() {
        var deleted=new java.util.HashSet<UUID>();
        return validatedSnapshot(deleted).map(snapshot->new DeletionSource(snapshot,java.util.Set.copyOf(deleted)));
    }

    /** 发布事务的最终权威回读，仍采用普通查询的严格来源策略。 */
    Optional<Snapshot> activeInTransaction() { return validatedSnapshot(); }

    private Optional<Snapshot> validatedSnapshot(java.util.Set<UUID> deleted) {
        try {
            var headers = jdbc.query(HEADER, this::header);
            if (headers.size() != 1) { throw new IllegalArgumentException("INVALID_INDEX_CONTROL"); }
            var header = headers.get(0);
            if (header.isEmpty()) { return Optional.empty(); }
            var expected = header.orElseThrow();
            var version = expected.version();
            var documents = jdbc.query(DOCUMENTS, (rs,row)->document(rs,row,deleted), version.generation().toString());
            var chunks = jdbc.query(CHUNKS, this::chunk, version.generation().toString());
            if (documents.size() != expected.documentCount() || chunks.size() != expected.chunkCount()) {
                throw new IllegalArgumentException("INCOMPLETE_INDEX");
            }
            var snapshot = new Snapshot(version, documents, chunks);
            if (!MessageDigest.isEqual(snapshotDigest.calculate(snapshot), expected.digest())) {
                throw new IllegalArgumentException("INVALID_CORPUS_DIGEST");
            }
            return Optional.of(snapshot);
        } catch (IllegalArgumentException invalid) {
            throw new KnowledgeRetrievalException(Kind.INDEX_INVALID);
        }
    }

    private Optional<Header> header(ResultSet rs, int row) throws SQLException {
        String active = rs.getString("active_id");
        if (active == null) { return Optional.empty(); }
        if (!active.equals(rs.getString("generation_id")) || !"ACTIVE".equals(rs.getString("status"))) {
            throw new IllegalArgumentException("INVALID_ACTIVE_GENERATION");
        }
        String profileId = rs.getString("profile_id");
        Integer dimension = (Integer) rs.getObject("dimension");
        if ((profileId == null) != (dimension == null)) { throw new IllegalArgumentException("INVALID_INDEX_PROFILE"); }
        Optional<EmbeddingProfile> profile = profileId == null ? Optional.empty()
                : Optional.of(new EmbeddingProfile(profileId, dimension));
        var version = new IndexVersion(UUID.fromString(active), rs.getLong("corpus_revision"), profile,
                rs.getString("tokenizer_version"), rs.getString("chunker_version"));
        int documents = rs.getInt("document_count");
        int chunks = rs.getInt("chunk_count");
        byte[] digest = rs.getBytes("corpus_digest");
        if (documents < 0 || documents > 100 || chunks < 0 || chunks > 2000 || digest == null || digest.length != 32) {
            throw new IllegalArgumentException("INVALID_INDEX_METADATA");
        }
        return Optional.of(new Header(version, documents, chunks, digest));
    }

    private KnowledgeDocument document(ResultSet rs, int row, java.util.Set<UUID> deleted) throws SQLException {
        long version = rs.getLong("version");
        boolean tombstone=deleted!=null && "DELETED".equals(rs.getString("document_status"))
                && rs.getTimestamp("deleted_at")!=null && rs.getObject("active_version")==null;
        if (!"READY".equals(rs.getString("version_status")) || (!tombstone &&
                (!"ACTIVE".equals(rs.getString("document_status")) || rs.getTimestamp("deleted_at") != null
                || rs.getLong("active_version") != version))) {
            throw new KnowledgeRetrievalException(Kind.SOURCE_CHANGED);
        }
        if(tombstone) deleted.add(UUID.fromString(rs.getString("document_id")));
        String text = rs.getString("original_text");
        verifyText(text, rs.getBytes("content_digest"));
        return new KnowledgeDocument(UUID.fromString(rs.getString("document_id")), rs.getString("source_key"),
                version, rs.getString("title"), rs.getString("locale"), rs.getInt("min_app_version"),
                rs.getInt("max_app_version"), text);
    }

    private KnowledgeChunk chunk(ResultSet rs, int row) throws SQLException {
        String text = rs.getString("chunk_text");
        verifyText(text, rs.getBytes("content_digest"));
        return new KnowledgeChunk(UUID.fromString(rs.getString("chunk_id")), UUID.fromString(rs.getString("document_id")),
                rs.getLong("document_version"), rs.getInt("ordinal"), rs.getString("heading"), text,
                rs.getInt("source_start"), rs.getInt("source_end"), rs.getString("chunker_version"));
    }

    private void verifyText(String text, byte[] expected) {
        if (text == null || expected == null || expected.length != 32) {
            throw new IllegalArgumentException("INVALID_TEXT_STORAGE");
        }
        try {
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(expected, actual)) { throw new IllegalArgumentException("INVALID_TEXT_DIGEST"); }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA256_UNAVAILABLE");
        }
    }

    private record Header(IndexVersion version, int documentCount, int chunkCount, byte[] digest) { }
    private record VectorRow(UUID id, float[] vector) { }
    record DeletionSource(Snapshot snapshot,java.util.Set<UUID> deleted) { }
}
