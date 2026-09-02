package com.aifriend.consent.infrastructure;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aifriend.consent.application.ConsentRecordPort;
import com.aifriend.consent.domain.ConsentRecord;
import com.aifriend.consent.domain.ConsentType;

/**
 * MySQL 追加式授权记录适配器。
 *
 * <p>INSERT IGNORE 与唯一键共同保证并发幂等；历史记录永不覆盖。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JpaConsentRecordAdapter implements ConsentRecordPort {

    private static final String INSERT_SQL = """
            INSERT IGNORE INTO consent_record (
                id, user_id, type, decision, policy_version, confirmed_at,
                decided_at, idempotency_key_hash, request_hash
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final ConsentRecordJpaRepository repository;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建授权记录适配器。
     *
     * @param repository 授权记录 Repository
     * @param jdbcTemplate JDBC 模板
     */
    public JpaConsentRecordAdapter(ConsentRecordJpaRepository repository, JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询用户全部授权历史并按数据库追加顺序倒序排列。
     *
     * @param userId 用户 UUID
     * @return 授权历史记录
     */
    @Override
    public List<ConsentRecord> listByUser(UUID userId) {
        return repository.findByUserIdOrderBySequenceNoDesc(userId).stream()
                .map(this::toDomain)
                .toList();
    }

    /**
     * 查询指定授权类型的最新决定。
     *
     * @param userId 用户 UUID
     * @param type 授权类型
     * @return 最新授权记录，尚未决定时为空
     */
    @Override
    public Optional<ConsentRecord> findLatest(UUID userId, ConsentType type) {
        return repository.findFirstByUserIdAndTypeOrderBySequenceNoDesc(userId, type)
                .map(this::toDomain);
    }

    /**
     * 按用户、授权类型和幂等键摘要查询原授权记录。
     *
     * @param userId 用户 UUID
     * @param type 授权类型
     * @param idempotencyKeyHash 幂等键 SHA-256 摘要
     * @return 匹配的原授权记录，不存在时为空
     */
    @Override
    public Optional<ConsentRecord> findByIdempotencyKey(
            UUID userId,
            ConsentType type,
            byte[] idempotencyKeyHash) {
        return repository.findByUserIdAndTypeAndIdempotencyKeyHash(userId, type, idempotencyKeyHash)
                .map(this::toDomain);
    }

    /**
     * 通过数据库唯一键追加授权记录，绝不覆盖历史记录。
     *
     * @param record 新授权记录
     * @return true 表示本次成功插入，false 表示幂等键已存在
     */
    @Override
    public boolean append(ConsentRecord record) {
        int inserted = jdbcTemplate.update(
                INSERT_SQL,
                uuidBytes(record.id()),
                uuidBytes(record.userId()),
                record.type().name(),
                record.decision().name(),
                record.policyVersion(),
                Timestamp.from(record.confirmedAt()),
                Timestamp.from(record.decidedAt()),
                record.idempotencyKeyHash(),
                record.requestHash());
        return inserted == 1;
    }

    private ConsentRecord toDomain(ConsentRecordEntity entity) {
        return new ConsentRecord(
                entity.getId(),
                entity.getUserId(),
                entity.getType(),
                entity.getDecision(),
                entity.getPolicyVersion(),
                entity.getConfirmedAt(),
                entity.getDecidedAt(),
                entity.getIdempotencyKeyHash().clone(),
                entity.getRequestHash().clone());
    }

    private byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }
}
