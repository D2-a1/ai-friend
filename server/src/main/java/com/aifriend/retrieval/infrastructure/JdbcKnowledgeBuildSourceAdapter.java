package com.aifriend.retrieval.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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

import com.aifriend.retrieval.application.KnowledgeBuildSourcePort;
import com.aifriend.retrieval.application.KnowledgeImportLeasePort.Claim;
import com.aifriend.retrieval.application.KnowledgeImportRegistrationPort.BuildSpecification;
import com.aifriend.retrieval.domain.EmbeddingProfile;
import com.aifriend.retrieval.domain.KnowledgeDocument;

/**
 * 新索引的完整来源读取；控制行锁内读取当前活动版本，再替换本job目标。
 * 不调用模型、不修改活动指针。读取完整性不能代替最终发布CAS。
 * @author codex
 * @since 1.0.0
 */
public final class JdbcKnowledgeBuildSourceAdapter implements KnowledgeBuildSourcePort {
    private static final String SOURCE_COLUMNS = """
            SELECT BIN_TO_UUID(d.id) document_id, d.source_key, d.status document_status,
                   d.deleted_at, d.active_version, v.version, v.title, v.locale, v.min_app_version,
                   v.max_app_version, v.original_text, v.content_digest, v.status version_status, v.chunker_version
            FROM knowledge_document d
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    /**
     * 为完整来源读取使用独立事务及有限SQL等待。
     * @param source 数据源
     * @param transactions 同数据源事务管理器
     */
    public JdbcKnowledgeBuildSourceAdapter(DataSource source, PlatformTransactionManager transactions) {
        this(new JdbcTemplate(Objects.requireNonNull(source, "source")), transactions);
        jdbc.setQueryTimeout(2);
    }

    JdbcKnowledgeBuildSourceAdapter(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transaction = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(2);
    }

    /** {@inheritDoc} */
    @Override public Source load(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        return transaction.execute(status -> loadLocked(claim));
    }

    // 仅同包发布器在已经开启的短事务中调用，复用控制行/任务锁，禁止另开事务自锁。
    Source loadLocked(Claim claim) {
            checkControl(claim);
            var targets = jdbc.query("""
                    SELECT BIN_TO_UUID(document_id) document_id, document_version, status, version,
                           BIN_TO_UUID(lease_token) lease_token, lease_until, deadline,
                           profile_id, dimension, tokenizer_version, chunker_version
                    FROM knowledge_import_job WHERE id=UUID_TO_BIN(?) FOR UPDATE
                    """, (rs, row) -> target(rs, claim), claim.job().id().toString());
            if (targets.size() != 1) { throw lost(); }
            Target target = targets.get(0);
            List<KnowledgeDocument> documents = jdbc.query(SOURCE_COLUMNS + """
                    LEFT JOIN knowledge_document_version v ON v.document_id=d.id AND v.version=d.active_version
                    WHERE d.status='ACTIVE' AND d.active_version IS NOT NULL
                    ORDER BY d.id LIMIT 101
                    """, (rs, row) -> {
                        if (!"READY".equals(rs.getString("version_status"))
                                || rs.getLong("active_version") != rs.getLong("version")) {
                            throw new IllegalArgumentException("INVALID_ACTIVE_SOURCE");
                        }
                        return document(rs);
                    });
            if (documents.size() > 100) { throw new IllegalArgumentException("BUILD_SOURCE_LIMIT"); }
            var pending = jdbc.query(SOURCE_COLUMNS + """
                    JOIN knowledge_document_version v ON v.document_id=d.id
                    WHERE d.id=UUID_TO_BIN(?) AND v.version=?
                    """, (rs, row) -> {
                        if (rs.getLong("active_version") >= target.version()) { throw lost(); }
                        if (!("BUILDING".equals(rs.getString("version_status")) || "READY".equals(rs.getString("version_status")))
                                || !target.specification().chunkerVersion().equals(rs.getString("chunker_version"))) {
                            throw new IllegalArgumentException("INVALID_TARGET_SOURCE");
                        }
                        return document(rs);
                    }, target.documentId().toString(), target.version());
            if (pending.size() != 1 || !pending.get(0).id().equals(target.documentId())
                    || pending.get(0).version() != target.version()) { throw lost(); }
            var complete = new ArrayList<>(documents);
            complete.removeIf(doc -> doc.id().equals(target.documentId()));
            complete.add(pending.get(0));
            checkTime(claim);
            return new Source(claim, target.specification(), target.documentId(), target.version(), complete);
    }

    private void checkControl(Claim claim) {
        List<Boolean> rows = jdbc.query("""
                SELECT version, corpus_revision, BIN_TO_UUID(lease_job_id) lease_job_id,
                       BIN_TO_UUID(lease_token) lease_token, lease_until
                FROM knowledge_index_control WHERE singleton_id=1 FOR UPDATE
                """, (rs, row) -> rs.getLong("version") == claim.controlVersion()
                && rs.getLong("corpus_revision") == claim.corpusRevision()
                && claim.job().id().toString().equals(rs.getString("lease_job_id"))
                && claim.job().leaseToken().orElseThrow().toString().equals(rs.getString("lease_token"))
                && timestampEquals(rs.getTimestamp("lease_until"), claim.job().leaseUntil().orElseThrow()));
        if (rows.size() != 1 || !rows.get(0)) { throw lost(); }
        checkTime(claim);
    }

    private Target target(ResultSet rs, Claim claim) throws SQLException {
        if (!"PROCESSING".equals(rs.getString("status")) || rs.getLong("version") != claim.job().version()
                || !claim.job().leaseToken().orElseThrow().toString().equals(rs.getString("lease_token"))
                || !timestampEquals(rs.getTimestamp("lease_until"), claim.job().leaseUntil().orElseThrow())
                || !timestampEquals(rs.getTimestamp("deadline"), claim.job().deadline())) { throw lost(); }
        String profile = rs.getString("profile_id");
        Integer dimension = (Integer) rs.getObject("dimension");
        if ((profile == null) != (dimension == null)) { throw new IllegalArgumentException("INVALID_BUILD_PROFILE"); }
        var specification = new BuildSpecification(profile == null ? Optional.empty() : Optional.of(new EmbeddingProfile(profile, dimension)),
                rs.getString("tokenizer_version"), rs.getString("chunker_version"));
        long version = rs.getLong("document_version");
        if (version < 1) { throw new IllegalArgumentException("INVALID_BUILD_VERSION"); }
        return new Target(UUID.fromString(rs.getString("document_id")), version, specification);
    }

    private KnowledgeDocument document(ResultSet rs) throws SQLException {
        if (!"ACTIVE".equals(rs.getString("document_status")) || rs.getTimestamp("deleted_at") != null) { throw lost(); }
        String text = rs.getString("original_text");
        byte[] expected = rs.getBytes("content_digest");
        if (text == null || expected == null || expected.length != 32) { throw new IllegalArgumentException("INVALID_SOURCE_DIGEST"); }
        try {
            if (!MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))) {
                throw new IllegalArgumentException("INVALID_SOURCE_DIGEST");
            }
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
        return new KnowledgeDocument(UUID.fromString(rs.getString("document_id")), rs.getString("source_key"),
                rs.getLong("version"), rs.getString("title"), rs.getString("locale"),
                rs.getInt("min_app_version"), rs.getInt("max_app_version"), text);
    }

    private void checkTime(Claim claim) {
        Timestamp time = jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", Timestamp.class);
        if (time == null) { throw new IllegalStateException("DATABASE_CLOCK_UNAVAILABLE"); }
        Instant now = time.toInstant();
        if (!now.isBefore(claim.job().leaseUntil().orElseThrow()) || !now.isBefore(claim.job().deadline())) { throw lost(); }
    }
    private boolean timestampEquals(Timestamp actual, Instant expected) { return actual != null && actual.toInstant().equals(expected); }
    private ConcurrencyFailureException lost() { return new ConcurrencyFailureException("BUILD_SOURCE_CHANGED"); }
    private record Target(UUID documentId, long version, BuildSpecification specification) { }
}
