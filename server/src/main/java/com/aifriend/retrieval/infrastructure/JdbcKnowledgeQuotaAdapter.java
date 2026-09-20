package com.aifriend.retrieval.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifriend.retrieval.application.KnowledgeQuotaPort;

/**
 * MySQL权威额度预留：同一控制行串行化跨实例计数和调用凭据。
 * 固定UTC窗口；只计请求/调用次数，不把缺失usage当作零消耗，不自动退款。
 * @author codex
 * @since 1.0.0
 */
public final class JdbcKnowledgeQuotaAdapter implements KnowledgeQuotaPort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final KnowledgeQuotaProperties properties;

    /**
     * 使用独立短事务，不在数据库事务中调用外部模型。
     * @param source 受控数据源
     * @param transactions 对应数据源事务管理器
     * @param properties 经校验的额度
     */
    public JdbcKnowledgeQuotaAdapter(DataSource source, PlatformTransactionManager transactions, KnowledgeQuotaProperties properties) {
        this(new JdbcTemplate(Objects.requireNonNull(source, "source")), transactions, properties);
        jdbc.setQueryTimeout(2);
    }

    JdbcKnowledgeQuotaAdapter(JdbcTemplate jdbc, PlatformTransactionManager transactions, KnowledgeQuotaProperties properties) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.properties = Objects.requireNonNull(properties, "properties");
        transaction = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(2);
    }

    /** {@inheritDoc} */
    @Override public Decision reserve(Reservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        if (!properties.enabled()) { return Decision.DISABLED; }
        byte[] digest = sha(reservation.operationId() + "\n" + reservation.ownerId().map(UUID::toString).orElse("-")
                + "\n" + reservation.phase() + "\n" + reservation.profileId() + "\n" + reservation.attempt()
                + "\n" + reservation.sequence() + "\n" + reservation.deadline());
        return transaction.execute(status -> {
            var clocks = jdbc.query("SELECT last_seen_at FROM knowledge_quota_control WHERE singleton_id=1 FOR UPDATE",
                    (rs, row) -> rs.getTimestamp("last_seen_at"));
            Timestamp dbNow = jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", Timestamp.class);
            if (clocks.size() != 1 || clocks.get(0) == null || dbNow == null || dbNow.before(clocks.get(0))) {
                throw new IllegalStateException("QUOTA_CLOCK_UNRELIABLE");
            }
            Instant now = dbNow.toInstant();
            requireOne(jdbc.update("UPDATE knowledge_quota_control SET last_seen_at=? WHERE singleton_id=1", dbNow));
            if (!now.isBefore(reservation.deadline())) { return Decision.EXPIRED; }
            long maxSeconds = reservation.phase() == Phase.IMPORT_EMBEDDING ? 1800 : 8;
            if (reservation.deadline().isAfter(now.plusSeconds(maxSeconds))) {
                throw new IllegalArgumentException("INVALID_QUOTA_DEADLINE");
            }
            var existing = jdbc.query("""
                    SELECT request_digest, decision FROM knowledge_quota_reservation
                    WHERE operation_id=UUID_TO_BIN(?) AND phase=? AND attempt=? AND sequence_no=?
                    """, (rs, row) -> {
                        if (!MessageDigest.isEqual(digest, Objects.requireNonNullElseGet(rs.getBytes("request_digest"), () -> new byte[0]))) {
                            throw new IllegalArgumentException("QUOTA_RESERVATION_CONFLICT");
                        }
                        var stored = Decision.valueOf(rs.getString("decision"));
                        if (stored != Decision.GRANTED && stored != Decision.LIMIT_EXCEEDED) {
                            throw new IllegalArgumentException("INVALID_QUOTA_STORAGE");
                        }
                        return stored == Decision.GRANTED ? Decision.DUPLICATE : stored;
                    }, reservation.operationId().toString(), reservation.phase().name(), reservation.attempt(), reservation.sequence());
            if (existing.size() > 1) { throw new IllegalArgumentException("INVALID_QUOTA_STORAGE"); }
            if (!existing.isEmpty()) { return existing.get(0); }
            var buckets = buckets(reservation, now);
            var counts = new ArrayList<Count>();
            for (var bucket : buckets) {
                var rows = jdbc.query("""
                        SELECT used_count FROM knowledge_quota_bucket
                        WHERE scope_hash=? AND window_kind=? AND window_start=?
                        """, (rs, row) -> {
                            Long count = (Long) rs.getObject("used_count");
                            if (count == null || count < 0 || count > 1_000_000) {
                                throw new IllegalArgumentException("INVALID_QUOTA_STORAGE");
                            }
                            return count;
                        }, bucket.hash(), bucket.kind(), Timestamp.from(bucket.start()));
                if (rows.size() > 1) { throw new IllegalArgumentException("INVALID_QUOTA_STORAGE"); }
                counts.add(new Count(bucket, rows.isEmpty() ? 0 : rows.get(0), !rows.isEmpty()));
            }
            Decision decision = counts.stream().anyMatch(count -> count.used() >= count.bucket().limit())
                    ? Decision.LIMIT_EXCEEDED : Decision.GRANTED;
            if (decision == Decision.GRANTED) {
                for (var count : counts) { increment(count, now); }
            }
            requireOne(jdbc.update("""
                    INSERT INTO knowledge_quota_reservation
                    (operation_id, phase, attempt, sequence_no, owner_id, profile_id, request_digest, decision, deadline, created_at, expires_at)
                    SELECT UUID_TO_BIN(?), ?, ?, ?, UUID_TO_BIN(?), ?, ?, ?, ?, ?, ?
                    WHERE UTC_TIMESTAMP(3)>=? AND UTC_TIMESTAMP(3)<?
                    """, reservation.operationId().toString(), reservation.phase().name(), reservation.attempt(), reservation.sequence(),
                    reservation.ownerId().map(UUID::toString).orElse(null), reservation.profileId(), digest, decision.name(),
                    Timestamp.from(reservation.deadline()), dbNow, Timestamp.from(reservation.deadline().plus(2, ChronoUnit.DAYS)),
                    dbNow, Timestamp.from(reservation.deadline())));
            return decision;
        });
    }

    private List<Bucket> buckets(Reservation reservation, Instant now) {
        if (reservation.phase() == Phase.REQUEST) {
            UUID owner = reservation.ownerId().orElseThrow();
            return List.of(new Bucket(sha("owner:" + owner), owner, "MINUTE",
                    now.truncatedTo(ChronoUnit.MINUTES), properties.ownerRequestsPerMinute()));
        }
        boolean importing = reservation.phase() == Phase.IMPORT_EMBEDDING;
        String lane = importing ? "import" : "online";
        long hourly = importing ? properties.importHourlyCalls() : properties.onlineHourlyCalls();
        long daily = importing ? properties.importDailyCalls() : properties.onlineDailyCalls();
        var buckets = new ArrayList<Bucket>();
        addPair(buckets, "global-model", now, properties.globalHourlyCalls(), properties.globalDailyCalls());
        addPair(buckets, "lane:" + lane, now, hourly, daily);
        // profile独立计数，但切profile不能重置global或lane总预算。
        addPair(buckets, "profile:" + lane + ":" + reservation.profileId(), now, hourly, daily);
        return List.copyOf(buckets);
    }

    private void addPair(List<Bucket> result, String key, Instant now, long hourly, long daily) {
        result.add(new Bucket(sha(key), null, "HOUR", now.truncatedTo(ChronoUnit.HOURS), hourly));
        result.add(new Bucket(sha(key), null, "DAY", now.truncatedTo(ChronoUnit.DAYS), daily));
    }

    private void increment(Count count, Instant now) {
        var bucket = count.bucket();
        if (count.exists()) {
            requireOne(jdbc.update("""
                    UPDATE knowledge_quota_bucket SET used_count=used_count+1
                    WHERE scope_hash=? AND window_kind=? AND window_start=? AND used_count=?
                    """, bucket.hash(), bucket.kind(), Timestamp.from(bucket.start()), count.used()));
        } else {
            requireOne(jdbc.update("""
                    INSERT INTO knowledge_quota_bucket (scope_hash,owner_id,window_kind,window_start,used_count,expires_at)
                    VALUES (?,UUID_TO_BIN(?),?,?,1,?)
                    """, bucket.hash(), bucket.owner() == null ? null : bucket.owner().toString(), bucket.kind(),
                    Timestamp.from(bucket.start()), Timestamp.from(now.plus(2, ChronoUnit.DAYS))));
        }
    }

    private static void requireOne(int count) {
        if (count != 1) { throw new ConcurrencyFailureException("QUOTA_RESERVATION_NOT_COMMITTED"); }
    }
    private static byte[] sha(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
    }
    private record Bucket(byte[] hash, UUID owner, String kind, Instant start, long limit) { }
    private record Count(Bucket bucket, long used, boolean exists) { }
}
