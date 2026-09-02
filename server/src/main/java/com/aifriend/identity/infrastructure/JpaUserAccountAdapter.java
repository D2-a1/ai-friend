package com.aifriend.identity.infrastructure;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aifriend.identity.application.UserAccountPort;
import com.aifriend.identity.domain.ProtectedWechatSubject;
import com.aifriend.identity.domain.UserAccount;
import com.aifriend.identity.domain.UserStatus;

/**
 * MySQL 用户账号持久化适配器。
 *
 * <p>使用 INSERT IGNORE 配合微信主体唯一键消除并发首次登录的重复账号竞态。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JpaUserAccountAdapter implements UserAccountPort {

    private static final String INSERT_USER_SQL = """
            INSERT IGNORE INTO app_user (
                id, wechat_open_id_cipher, wechat_open_id_hash, status,
                account_generation, accessibility_settings, alias_namespace_version,
                version, created_at, updated_at
            ) VALUES (?, ?, ?, 'ACTIVE', ?, JSON_OBJECT(), 0, 0, ?, ?)
            """;

    private final AppUserJpaRepository repository;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建用户账号持久化适配器。
     *
     * @param repository 用户 JPA Repository
     * @param jdbcTemplate JDBC 模板
     */
    public JpaUserAccountAdapter(AppUserJpaRepository repository, JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按微信主体 HMAC 查询账号。
     *
     * @param subjectHash 微信主体查询键
     * @return 用户账号
     */
    @Override
    public Optional<UserAccount> findByWechatSubjectHash(byte[] subjectHash) {
        return repository.findByWechatOpenIdHash(subjectHash).map(this::toDomain);
    }

    /**
     * 按内部 UUID 查询账号。
     *
     * @param userId 用户 UUID
     * @return 用户账号
     */
    @Override
    public Optional<UserAccount> findById(UUID userId) {
        return repository.findById(userId).map(this::toDomain);
    }

    /**
     * 并发安全创建指定代次账号。
     *
     * @param protectedSubject 加密主体和查询键
     * @param accountGeneration 账号代次
     * @param createdAt 创建时间
     * @return 新建或并发已存在账号
     */
    @Override
    public UserAccount create(
            ProtectedWechatSubject protectedSubject,
            long accountGeneration,
            Instant createdAt) {
        if (accountGeneration < 1L) {
            throw new IllegalArgumentException("账号代次必须大于零");
        }
        UUID userId = UUID.randomUUID();
        Timestamp timestamp = Timestamp.from(createdAt);
        jdbcTemplate.update(
                INSERT_USER_SQL,
                uuidBytes(userId),
                protectedSubject.cipher(),
                protectedSubject.hash(),
                accountGeneration,
                timestamp,
                timestamp);
        return repository.findByWechatOpenIdHash(protectedSubject.hash())
                .map(this::toDomain)
                .orElseThrow(() -> new IllegalStateException("用户账号创建后未找到"));
    }

    private UserAccount toDomain(AppUserEntity entity) {
        return new UserAccount(
                entity.getId(),
                entity.getStatus(),
                entity.getAccountGeneration(),
                entity.getCreatedAt());
    }

    private byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }
}
