package com.aifriend.template.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aifriend.task.domain.TaskIntent;
import com.aifriend.template.application.RoutineCommandDeletionRecord;
import com.aifriend.template.application.RoutineCommandTemplateRecord;
import com.aifriend.template.application.RoutineCommandTemplateStorePort;
import com.aifriend.template.application.RoutineCommandTemplateWrite;

/**
 * MySQL 日常指令模板与删除幂等事实适配器。
 *
 * <p>全部 SQL 均参数化；owner 命名空间通过数据库行锁串行化删除与学习写入。
 * 工作器只在事务外短期解密模板材料，数据库适配器不记录密文、摘要或明文内容。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class RoutineCommandTemplateJdbcAdapter
        implements RoutineCommandTemplateStorePort {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建日常指令模板 JDBC 适配器。
     *
     * @param jdbcTemplate 参数化数据库访问组件
     */
    public RoutineCommandTemplateJdbcAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public long lockNamespace(UUID ownerUserId, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO routine_command_namespace(owner_user_id,version,updated_at) "
                        + "VALUES(UUID_TO_BIN(?),1,?) ON DUPLICATE KEY UPDATE "
                        + "owner_user_id=owner_user_id",
                ownerUserId.toString(),
                Timestamp.from(now));
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM routine_command_namespace "
                        + "WHERE owner_user_id=UUID_TO_BIN(?) FOR UPDATE",
                Long.class,
                ownerUserId.toString());
        if (version == null || version < 1) {
            throw new IllegalStateException("日常指令命名空间版本无效");
        }
        return version;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<RoutineCommandDeletionRecord> findDeletion(
            UUID ownerUserId,
            byte[] idempotencyKeyHash) {
        List<RoutineCommandDeletionRecord> records = jdbcTemplate.query(
                "SELECT request_hash,deleted_count,deleted_at,namespace_version_after "
                        + "FROM routine_command_deletion WHERE owner_user_id=UUID_TO_BIN(?) "
                        + "AND idempotency_key_hash=?",
                (resultSet, rowNumber) -> new RoutineCommandDeletionRecord(
                        resultSet.getBytes("request_hash"),
                        resultSet.getInt("deleted_count"),
                        resultSet.getTimestamp("deleted_at").toInstant(),
                        resultSet.getLong("namespace_version_after")),
                ownerUserId.toString(),
                idempotencyKeyHash);
        if (records.size() > 1) {
            throw new IllegalStateException("日常指令删除幂等事实不唯一");
        }
        return records.stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public int countByOwner(UUID ownerUserId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM routine_command_template "
                        + "WHERE owner_user_id=UUID_TO_BIN(?)",
                Long.class,
                ownerUserId.toString());
        if (count == null || count > Integer.MAX_VALUE) {
            throw new IllegalStateException("日常指令模板数量无效");
        }
        return count.intValue();
    }

    /** {@inheritDoc} */
    @Override
    public int deleteAllByOwner(UUID ownerUserId) {
        return jdbcTemplate.update(
                "DELETE FROM routine_command_template WHERE owner_user_id=UUID_TO_BIN(?)",
                ownerUserId.toString());
    }

    /** {@inheritDoc} */
    @Override
    public List<RoutineCommandTemplateRecord> findByOwnerAndIntent(
            UUID ownerUserId,
            TaskIntent intent) {
        return jdbcTemplate.query("""
                SELECT BIN_TO_UUID(id) id,intent,dialect_code,
                    dialect_package_version,template_model_version,threshold_version,
                    template_cipher,template_digest,usage_count,last_confirmed_at,
                    version,updated_at
                FROM routine_command_template
                WHERE owner_user_id=UUID_TO_BIN(?) AND intent=?
                ORDER BY last_confirmed_at,id
                """,
                (resultSet, rowNumber) -> mapTemplate(resultSet),
                ownerUserId.toString(),
                intent.name());
    }

    /** {@inheritDoc} */
    @Override
    public List<RoutineCommandTemplateRecord> findAllByOwner(UUID ownerUserId) {
        return jdbcTemplate.query("""
                SELECT BIN_TO_UUID(id) id,intent,dialect_code,
                    dialect_package_version,template_model_version,threshold_version,
                    template_cipher,template_digest,usage_count,last_confirmed_at,
                    version,updated_at
                FROM routine_command_template
                WHERE owner_user_id=UUID_TO_BIN(?)
                ORDER BY last_confirmed_at,id
                """,
                (resultSet, rowNumber) -> mapTemplate(resultSet),
                ownerUserId.toString());
    }

    /** {@inheritDoc} */
    @Override
    public void insert(UUID ownerUserId, RoutineCommandTemplateWrite template) {
        Timestamp confirmedAt = Timestamp.from(template.confirmedAt());
        int changed = jdbcTemplate.update("""
                INSERT INTO routine_command_template(
                    id,owner_user_id,intent,dialect_code,dialect_package_version,
                    template_model_version,threshold_version,template_cipher,
                    template_digest,usage_count,last_confirmed_at,status,version,
                    created_at,updated_at)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,?,?,?,?,1,?,
                    'ACTIVE',0,?,?)
                """,
                template.id().toString(),
                ownerUserId.toString(),
                template.intent().name(),
                template.dialectCode(),
                template.dialectPackageVersion(),
                template.templateModelVersion(),
                template.thresholdVersion(),
                template.templateCipher(),
                template.templateDigest(),
                confirmedAt,
                confirmedAt,
                confirmedAt);
        requireSingleChange(changed);
    }

    /** {@inheritDoc} */
    @Override
    public void incrementUsage(
            UUID ownerUserId,
            UUID templateId,
            long expectedVersion,
            Instant confirmedAt) {
        int changed = jdbcTemplate.update("""
                UPDATE routine_command_template
                SET usage_count=usage_count+1,last_confirmed_at=?,version=version+1,
                    updated_at=?
                WHERE owner_user_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                    AND version=? AND usage_count<2147483647
                """,
                Timestamp.from(confirmedAt),
                Timestamp.from(confirmedAt),
                ownerUserId.toString(),
                templateId.toString(),
                expectedVersion);
        requireSingleChange(changed);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteExact(
            UUID ownerUserId,
            UUID templateId,
            long expectedVersion) {
        int changed = jdbcTemplate.update(
                "DELETE FROM routine_command_template "
                        + "WHERE owner_user_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?) "
                        + "AND version=?",
                ownerUserId.toString(),
                templateId.toString(),
                expectedVersion);
        requireSingleChange(changed);
    }

    private RoutineCommandTemplateRecord mapTemplate(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new RoutineCommandTemplateRecord(
                UUID.fromString(resultSet.getString("id")),
                TaskIntent.valueOf(resultSet.getString("intent")),
                resultSet.getString("dialect_code"),
                resultSet.getString("dialect_package_version"),
                resultSet.getString("template_model_version"),
                resultSet.getString("threshold_version"),
                resultSet.getBytes("template_cipher"),
                resultSet.getBytes("template_digest"),
                resultSet.getInt("usage_count"),
                resultSet.getTimestamp("last_confirmed_at").toInstant(),
                resultSet.getLong("version"),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private void requireSingleChange(int changed) {
        if (changed != 1) {
            throw new IllegalStateException("日常指令模板并发写入失败");
        }
    }

    /** {@inheritDoc} */
    @Override
    public void updateNamespace(
            UUID ownerUserId,
            long expectedVersion,
            long nextVersion,
            Instant now) {
        int changed = jdbcTemplate.update(
                "UPDATE routine_command_namespace SET version=?,updated_at=? "
                        + "WHERE owner_user_id=UUID_TO_BIN(?) AND version=?",
                nextVersion,
                Timestamp.from(now),
                ownerUserId.toString(),
                expectedVersion);
        if (changed != 1) {
            throw new IllegalStateException("日常指令命名空间并发更新失败");
        }
    }

    /** {@inheritDoc} */
    @Override
    public void saveDeletion(
            UUID ownerUserId,
            byte[] idempotencyKeyHash,
            byte[] requestHash,
            Long expectedVersion,
            int deletedCount,
            long namespaceVersionAfter,
            Instant deletedAt) {
        int changed = jdbcTemplate.update(
                "INSERT INTO routine_command_deletion(id,owner_user_id,idempotency_key_hash,"
                        + "request_hash,expected_version,deleted_count,namespace_version_after,"
                        + "deleted_at,created_at) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),
                ownerUserId.toString(),
                idempotencyKeyHash,
                requestHash,
                expectedVersion,
                deletedCount,
                namespaceVersionAfter,
                Timestamp.from(deletedAt),
                Timestamp.from(deletedAt));
        if (changed != 1) {
            throw new IllegalStateException("日常指令删除幂等事实写入失败");
        }
    }
}
