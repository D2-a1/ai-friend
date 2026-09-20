package com.aifriend.retrieval.infrastructure;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifriend.retrieval.application.KnowledgeImportException;
import com.aifriend.retrieval.application.KnowledgeImportRegistrationPort;
import com.aifriend.retrieval.domain.KnowledgeImportJob;
import com.aifriend.retrieval.domain.KnowledgeImportRequest;

/**
 * 管理导入登记：控制行锁内登记新文档版本及job，原活动版本保持不变。
 * 不发送HTTP、不启动异步线程；由导入条件配置装配，经独立管理权限入口调用。
 * @author codex
 * @since 1.0.0
 */
public final class JdbcKnowledgeImportRegistrationAdapter implements KnowledgeImportRegistrationPort {
    private static final String RECEIPT = """
            SELECT BIN_TO_UUID(j.id) id, BIN_TO_UUID(j.document_id) document_id,
                   j.document_version, j.status, j.attempts, j.error_code, j.request_digest,
                   d.status document_status
            FROM knowledge_import_job j JOIN knowledge_document d ON d.id=j.document_id
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate write;
    private final TransactionTemplate read;
    private final Duration jobLifetime;
    private final int maximumPendingJobs;

    /**
     * 构建独立有界事务组件，不调整已有数据源或运行配置。
     * @param source 受控数据源
     * @param transactions 同数据源事务管理器
     * @param jobLifetime 总任务有效期，1秒至30分钟
     * @param maximumPendingJobs 全局待处理上限，1至100
     */
    public JdbcKnowledgeImportRegistrationAdapter(DataSource source, PlatformTransactionManager transactions,
            Duration jobLifetime, int maximumPendingJobs) {
        this(new JdbcTemplate(Objects.requireNonNull(source, "source")), transactions, jobLifetime, maximumPendingJobs);
        jdbc.setQueryTimeout(2);
    }

    JdbcKnowledgeImportRegistrationAdapter(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            Duration jobLifetime, int maximumPendingJobs) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        if (jobLifetime == null || jobLifetime.compareTo(Duration.ofSeconds(1)) < 0
                || jobLifetime.compareTo(Duration.ofMinutes(30)) > 0 || jobLifetime.toMillis() % 1000 != 0
                || jobLifetime.getNano() != 0 || maximumPendingJobs < 1 || maximumPendingJobs > 100) {
            throw new IllegalArgumentException("INVALID_IMPORT_LIMITS");
        }
        this.jobLifetime = jobLifetime;
        this.maximumPendingJobs = maximumPendingJobs;
        write = transaction(transactions, false);
        read = transaction(transactions, true);
    }

    /** {@inheritDoc} */
    @Override public Receipt register(KnowledgeImportRequest request, BuildSpecification specification) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(specification, "specification");
        byte[] keyHash = hash(("knowledge-import-key-v1\n" + request.idempotencyKey()).getBytes(StandardCharsets.US_ASCII));
        byte[] requestHash = requestHash(request, specification);
        return write.execute(status -> {
            List<Long> controls = jdbc.query("SELECT version FROM knowledge_index_control WHERE singleton_id=1 FOR UPDATE",
                    (rs, row) -> rs.getLong("version"));
            if (controls.size() != 1 || controls.get(0) < 1) { throw new IllegalStateException("INVALID_INDEX_CONTROL"); }
            List<StoredReceipt> existing = jdbc.query(RECEIPT + " WHERE j.idempotency_key_hash=?", this::receipt, keyHash);
            if (existing.size() > 1) { throw new IllegalStateException("INVALID_IMPORT_KEY_STORAGE"); }
            if (!existing.isEmpty()) {
                var stored = existing.get(0);
                if (!MessageDigest.isEqual(requestHash, stored.digest())) {
                    throw new KnowledgeImportException(KnowledgeImportException.Kind.IDEMPOTENCY_CONFLICT);
                }
                return stored.receipt();
            }
            Long pending = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_import_job WHERE status IN ('PENDING','PROCESSING')", Long.class);
            if (pending == null || pending < 0) { throw new IllegalStateException("INVALID_IMPORT_COUNT"); }
            if (pending >= maximumPendingJobs) { throw new KnowledgeImportException(KnowledgeImportException.Kind.RESOURCE_LIMIT); }
            Instant now = databaseNow();
            List<Source> sources = jdbc.query("""
                    SELECT BIN_TO_UUID(id) id, status, row_version
                    FROM knowledge_document WHERE source_key=? FOR UPDATE
                    """, (rs, row) -> new Source(UUID.fromString(rs.getString("id")),
                    rs.getString("status"), rs.getLong("row_version")), request.sourceKey());
            if (sources.size() > 1) { throw new IllegalStateException("INVALID_SOURCE_STORAGE"); }
            UUID documentId;
            long version;
            if (sources.isEmpty()) {
                Long count = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE status='ACTIVE'", Long.class);
                if (count == null || count < 0) { throw new IllegalStateException("INVALID_SOURCE_COUNT"); }
                if (count >= 100) { throw new KnowledgeImportException(KnowledgeImportException.Kind.RESOURCE_LIMIT); }
                documentId = UUID.randomUUID();
                version = 1;
                requireOne(jdbc.update("""
                        INSERT INTO knowledge_document (id,source_key,status,active_version,row_version,created_at,updated_at)
                        VALUES (UUID_TO_BIN(?),?,'ACTIVE',NULL,1,?,?)
                        """, documentId.toString(), request.sourceKey(), Timestamp.from(now), Timestamp.from(now)));
            } else {
                var source = sources.get(0);
                if ("DELETED".equals(source.status())) { throw new KnowledgeImportException(KnowledgeImportException.Kind.SOURCE_DELETED); }
                if (!"ACTIVE".equals(source.status()) || source.version() < 1) { throw new IllegalStateException("INVALID_SOURCE_STORAGE"); }
                documentId = source.id();
                Long maximum = jdbc.queryForObject("SELECT COALESCE(MAX(version),0) FROM knowledge_document_version WHERE document_id=UUID_TO_BIN(?)",
                        Long.class, documentId.toString());
                if (maximum == null || maximum < 1) { throw new IllegalStateException("INVALID_SOURCE_VERSION"); }
                version = Math.addExact(maximum, 1);
                requireOne(jdbc.update("UPDATE knowledge_document SET row_version=row_version+1,updated_at=? WHERE id=UUID_TO_BIN(?) AND row_version=?",
                        Timestamp.from(now), documentId.toString(), source.version()));
            }
            requireOne(jdbc.update("""
                    INSERT INTO knowledge_document_version (document_id,version,title,locale,min_app_version,max_app_version,
                           original_text,content_digest,chunker_version,status,created_at)
                    VALUES (UUID_TO_BIN(?),?,?,?,?,?,?,?,?,'BUILDING',?)
                    """, documentId.toString(), version, request.title(), request.locale(), request.minimumAppVersion(),
                    request.maximumAppVersion(), request.text(), hash(request.text().getBytes(StandardCharsets.UTF_8)),
                    specification.chunkerVersion(), Timestamp.from(now)));
            UUID jobId = UUID.randomUUID();
            var profile = specification.embeddingProfile();
            requireOne(jdbc.update("""
                    INSERT INTO knowledge_import_job (id,document_id,document_version,idempotency_key_hash,request_digest,
                           profile_id,dimension,tokenizer_version,chunker_version,status,attempts,version,next_attempt_at,
                           deadline,error_code,created_at,updated_at)
                    VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,?,?,?,?,'PENDING',0,1,?,?,'NONE',?,?)
                    """, jobId.toString(), documentId.toString(), version, keyHash, requestHash,
                    profile.map(p -> p.id()).orElse(null), profile.map(p -> p.dimension()).orElse(null),
                    specification.tokenizerVersion(), specification.chunkerVersion(), Timestamp.from(now),
                    Timestamp.from(now.plus(jobLifetime)), Timestamp.from(now), Timestamp.from(now)));
            requireOne(jdbc.update("""
                    UPDATE knowledge_index_control SET corpus_revision=corpus_revision+1,version=version+1
                    WHERE singleton_id=1 AND version=?
                    """, controls.get(0)));
            return new Receipt(jobId, documentId, version, KnowledgeImportJob.State.PENDING, 0, KnowledgeImportJob.Failure.NONE);
        });
    }

    /** {@inheritDoc} */
    @Override public Optional<Receipt> find(UUID id) {
        Objects.requireNonNull(id, "id");
        return read.execute(status -> {
            var rows = jdbc.query(RECEIPT + " WHERE j.id=UUID_TO_BIN(?)", this::receipt, id.toString());
            if (rows.size() > 1) { throw new IllegalStateException("INVALID_IMPORT_JOB_STORAGE"); }
            return rows.stream().map(StoredReceipt::receipt).findFirst();
        });
    }

    private StoredReceipt receipt(ResultSet rs, int row) throws SQLException {
        if ("DELETED".equals(rs.getString("document_status"))) {
            throw new KnowledgeImportException(KnowledgeImportException.Kind.SOURCE_DELETED);
        }
        if (!"ACTIVE".equals(rs.getString("document_status"))) { throw new IllegalStateException("INVALID_SOURCE_STORAGE"); }
        byte[] digest = rs.getBytes("request_digest");
        if (digest == null || digest.length != 32) { throw new IllegalStateException("INVALID_IMPORT_DIGEST"); }
        return new StoredReceipt(new Receipt(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("document_id")),
                rs.getLong("document_version"), KnowledgeImportJob.State.valueOf(rs.getString("status")), rs.getInt("attempts"),
                KnowledgeImportJob.Failure.valueOf(rs.getString("error_code"))), digest);
    }

    private byte[] requestHash(KnowledgeImportRequest request, BuildSpecification specification) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                text(out, "knowledge-import-request-v1");
                text(out, request.idempotencyKey());
                text(out, request.sourceKey());
                text(out, request.title());
                text(out, request.text());
                text(out, request.locale());
                out.writeInt(request.minimumAppVersion());
                out.writeInt(request.maximumAppVersion());
                text(out, specification.tokenizerVersion());
                text(out, specification.chunkerVersion());
                out.writeBoolean(specification.embeddingProfile().isPresent());
                if (specification.embeddingProfile().isPresent()) {
                    var profile = specification.embeddingProfile().orElseThrow();
                    text(out, profile.id());
                    out.writeInt(profile.dimension());
                }
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new IllegalStateException("IMPORT_DIGEST_UNAVAILABLE");
        }
    }

    private byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
    }
    private void text(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
    private Instant databaseNow() {
        Timestamp time = jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", Timestamp.class);
        if (time == null) { throw new IllegalStateException("DATABASE_CLOCK_UNAVAILABLE"); }
        return time.toInstant();
    }
    private TransactionTemplate transaction(PlatformTransactionManager manager, boolean readOnly) {
        var template = new TransactionTemplate(Objects.requireNonNull(manager, "transactions"));
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setReadOnly(readOnly);
        template.setTimeout(2);
        return template;
    }
    private void requireOne(int count) {
        if (count != 1) { throw new ConcurrencyFailureException("IMPORT_REGISTRATION_CONFLICT"); }
    }
    private record Source(UUID id, String status, long version) { }
    private record StoredReceipt(Receipt receipt, byte[] digest) { }
}
