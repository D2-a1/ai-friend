package com.aifriend.personalization.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aifriend.personalization.application.PersonalMemoryRepositoryPort;
import com.aifriend.personalization.domain.PersonalMemoryRecord;
import com.aifriend.personalization.domain.PersonalMemoryStatus;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 长期个人偏好的参数化 JDBC 持久化适配器。
 *
 * <p>更新语句显式携带旧版本，避免 JPA merge 与业务资源版本双重递增。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JdbcPersonalMemoryAdapter implements PersonalMemoryRepositoryPort {

    private static final String COLUMNS = "UUID_FROM_BIN(owner_user_id) owner_user_id,"
            + "preferences_cipher,preferences_digest,policy_version,status,"
            + "update_idempotency_key_hash,update_request_hash,"
            + "delete_idempotency_key_hash,delete_request_hash,version,"
            + "created_at,updated_at,deleted_at";
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建 JDBC 适配器。
     *
     * @param jdbcTemplate 参数化数据库访问组件
     */
    public JdbcPersonalMemoryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PersonalMemoryRecord> findByOwner(UUID ownerUserId) {
        return query(ownerUserId, false);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PersonalMemoryRecord> findByOwnerForUpdate(UUID ownerUserId) {
        return query(ownerUserId, true);
    }

    /** {@inheritDoc} */
    @Override
    public PersonalMemoryRecord save(PersonalMemoryRecord record) {
        if (record.version() == 1L) {
            int inserted;
            try {
                inserted = jdbcTemplate.update(
                    "INSERT INTO personal_assistant_memory (owner_user_id,"
                            + "preferences_cipher,preferences_digest,policy_version,status,"
                            + "update_idempotency_key_hash,update_request_hash,"
                            + "delete_idempotency_key_hash,delete_request_hash,version,"
                            + "created_at,updated_at,deleted_at) VALUES (UUID_TO_BIN(?),"
                            + "?,?,?,?,?,?,?,?,?,?,?,?)",
                    record.ownerUserId().toString(), record.preferencesCipher(),
                    record.preferencesDigest(), record.policyVersion(), record.status().name(),
                    record.updateIdempotencyKeyHash(), record.updateRequestHash(),
                    record.deleteIdempotencyKeyHash(), record.deleteRequestHash(),
                    record.version(), Timestamp.from(record.createdAt()),
                    Timestamp.from(record.updatedAt()), timestamp(record.deletedAt()));
            } catch (DataIntegrityViolationException exception) {
                throw new BusinessException(ErrorCode.SESSION_CONFLICT);
            }
            if (inserted != 1) {
                throw new BusinessException(ErrorCode.SESSION_CONFLICT);
            }
            return record;
        }
        int updated = jdbcTemplate.update(
                "UPDATE personal_assistant_memory SET preferences_cipher=?,"
                        + "preferences_digest=?,policy_version=?,status=?,"
                        + "update_idempotency_key_hash=?,update_request_hash=?,"
                        + "delete_idempotency_key_hash=?,delete_request_hash=?,"
                        + "version=?,updated_at=?,deleted_at=? "
                        + "WHERE owner_user_id=UUID_TO_BIN(?) AND version=?",
                record.preferencesCipher(), record.preferencesDigest(),
                record.policyVersion(), record.status().name(),
                record.updateIdempotencyKeyHash(), record.updateRequestHash(),
                record.deleteIdempotencyKeyHash(), record.deleteRequestHash(),
                record.version(), Timestamp.from(record.updatedAt()),
                timestamp(record.deletedAt()), record.ownerUserId().toString(),
                record.version() - 1L);
        if (updated != 1) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        return record;
    }

    private Optional<PersonalMemoryRecord> query(UUID ownerUserId, boolean forUpdate) {
        String sql = "SELECT " + COLUMNS + " FROM personal_assistant_memory "
                + "WHERE owner_user_id=UUID_TO_BIN(?)" + (forUpdate ? " FOR UPDATE" : "");
        List<PersonalMemoryRecord> records = jdbcTemplate.query(
                sql, this::map, ownerUserId.toString());
        return records.stream().findFirst();
    }

    private PersonalMemoryRecord map(ResultSet resultSet, int rowNumber)
            throws SQLException {
        Timestamp deletedAt = resultSet.getTimestamp("deleted_at");
        return new PersonalMemoryRecord(
                UUID.fromString(resultSet.getString("owner_user_id")),
                resultSet.getBytes("preferences_cipher"),
                resultSet.getBytes("preferences_digest"),
                resultSet.getString("policy_version"),
                PersonalMemoryStatus.valueOf(resultSet.getString("status")),
                resultSet.getBytes("update_idempotency_key_hash"),
                resultSet.getBytes("update_request_hash"),
                resultSet.getBytes("delete_idempotency_key_hash"),
                resultSet.getBytes("delete_request_hash"),
                resultSet.getLong("version"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant(),
                deletedAt == null ? null : deletedAt.toInstant());
    }

    private Timestamp timestamp(java.time.Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
