package com.aifriend.retention.infrastructure;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifriend.retention.application.AccountClosureCleanupPort;
import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.voice.application.AudioObjectStoragePort;

/**
 * MySQL 与私有音频对象存储的账号注销有界清理适配器。
 *
 * <p>每次只推进一个有界阶段。对象存储删除发生在事务外；成功后才用短事务标记对象，
 * 防止外部 I/O 占用数据库锁。完成状态必须在锁内逐表复验后写入。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JdbcAccountClosureCleanupAdapter implements AccountClosureCleanupPort {
    /** 最长灾备留存三十天后再保留七天重放缓冲。 */
    private static final Duration TOMBSTONE_REPLAY_RETENTION = Duration.ofDays(37);
    /** 删除墓碑政策版本。 */
    private static final String TOMBSTONE_POLICY_VERSION = "deletion-tombstone-v1";
    /** 参数化数据库访问组件。 */
    private final JdbcTemplate jdbcTemplate;
    /** 私有音频对象存储端口。 */
    private final AudioObjectStoragePort storagePort;
    /** 对象键解密组件。 */
    private final SensitiveDataProtector protector;
    /** 有界数据库短事务模板。 */
    private final TransactionTemplate transactionTemplate;
    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建账号注销清理适配器。
     *
     * @param jdbcTemplate 参数化数据库访问组件
     * @param storagePort 私有音频对象存储端口
     * @param protector 对象键解密组件
     * @param transactionManager 数据库事务管理器
     * @param clock UTC 时钟
     */
    public JdbcAccountClosureCleanupAdapter(
            JdbcTemplate jdbcTemplate,
            AudioObjectStoragePort storagePort,
            SensitiveDataProtector protector,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.storagePort = storagePort;
        this.protector = protector;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** {@inheritDoc} */
    @Override
    public int cleanupBatch(UUID ownerUserId, int batchSize) {
        int safeBatchSize = requireBatchSize(batchSize);
        List<AudioRow> audioRows = findStoredAudio(ownerUserId, safeBatchSize);
        if (!audioRows.isEmpty()) {
            for (AudioRow row : audioRows) {
                storagePort.delete(protector.decrypt(row.objectKeyCipher()));
                transactionTemplate.executeWithoutResult(status ->
                        markAudioDeleted(ownerUserId, row.id(), Instant.now(clock)));
            }
            return audioRows.size();
        }
        Integer changed = transactionTemplate.execute(status ->
                cleanupDatabasePhase(ownerUserId, safeBatchSize));
        return changed == null ? 0 : changed;
    }

    /** {@inheritDoc} */
    @Override
    public boolean finalizeIfCleared(UUID jobId, UUID ownerUserId, Instant now) {
        Boolean completed = transactionTemplate.execute(status -> {
            ClosureRow closure = jdbcTemplate.queryForObject(
                    "SELECT account_generation,accepted_at,re_registration_not_before,status "
                            + "FROM account_closure_request WHERE id=UUID_TO_BIN(?) "
                            + "AND owner_user_id=UUID_TO_BIN(?) FOR UPDATE",
                    (resultSet, rowNumber) -> new ClosureRow(
                            resultSet.getLong("account_generation"),
                            resultSet.getTimestamp("accepted_at").toInstant(),
                            resultSet.getTimestamp("re_registration_not_before").toInstant(),
                            resultSet.getString("status")),
                    jobId.toString(),
                    ownerUserId.toString());
            if (closure == null || !"ACCEPTED".equals(closure.status())) {
                return false;
            }
            AccountRow account = jdbcTemplate.queryForObject(
                    "SELECT wechat_open_id_hash,account_generation,status FROM app_user "
                            + "WHERE id=UUID_TO_BIN(?) FOR UPDATE",
                    (resultSet, rowNumber) -> new AccountRow(
                            resultSet.getBytes("wechat_open_id_hash"),
                            resultSet.getLong("account_generation"),
                            resultSet.getString("status")),
                    ownerUserId.toString());
            if (account == null
                    || !"DELETING".equals(account.status())
                    || account.accountGeneration() != closure.accountGeneration()
                    || !allBusinessRowsCleared(ownerUserId)) {
                return false;
            }
            requireSubjectHash(account.subjectHash());
            Timestamp timestamp = Timestamp.from(now);
            UUID tombstoneId = insertDeletionTombstone(closure, account, now);
            int accountChanged = jdbcTemplate.update(
                    "UPDATE app_user SET wechat_open_id_cipher=NULL,wechat_open_id_hash=NULL,"
                            + "wechat_union_id_cipher=NULL,"
                            + "wechat_union_id_hash=NULL,dialect_preference=NULL,"
                            + "accessibility_settings=JSON_OBJECT(),alias_namespace_version=0,"
                            + "status='DELETED',updated_at=?,version=version+1 "
                            + "WHERE id=UUID_TO_BIN(?) AND status='DELETING'",
                    timestamp,
                    ownerUserId.toString());
            int closureChanged = jdbcTemplate.update(
                    "UPDATE account_closure_request SET status='COMPLETED',completed_at=?,"
                            + "last_attempt_at=?,next_attempt_at=?,updated_at=?,version=version+1 "
                            + "WHERE id=UUID_TO_BIN(?) AND owner_user_id=UUID_TO_BIN(?) "
                            + "AND status='ACCEPTED'",
                    timestamp,
                    timestamp,
                    timestamp,
                    timestamp,
                    jobId.toString(),
                    ownerUserId.toString());
            if (accountChanged != 1 || closureChanged != 1) {
                throw new IllegalStateException("账号注销完成状态写入不完整");
            }
            appendTombstoneEvent(tombstoneId, closure.accountGeneration(), now);
            appendCompletionEvent(jobId, now);
            return true;
        });
        return Boolean.TRUE.equals(completed);
    }

    private UUID insertDeletionTombstone(
            ClosureRow closure,
            AccountRow account,
            Instant completedAt) {
        UUID tombstoneId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO deletion_tombstone(id,subject_hash,old_account_generation,"
                        + "accepted_at,completed_at,"
                        + "re_registration_not_before,policy_version,replay_until,"
                        + "export_next_attempt_at,created_at) "
                        + "VALUES(UUID_TO_BIN(?),?,?,?,?,?,?,?,?,?)",
                tombstoneId.toString(),
                account.subjectHash(),
                closure.accountGeneration(),
                Timestamp.from(closure.acceptedAt()),
                Timestamp.from(completedAt),
                Timestamp.from(closure.reRegistrationNotBefore()),
                TOMBSTONE_POLICY_VERSION,
                Timestamp.from(completedAt.plus(TOMBSTONE_REPLAY_RETENTION)),
                Timestamp.from(completedAt),
                Timestamp.from(completedAt));
        return tombstoneId;
    }

    private List<AudioRow> findStoredAudio(UUID ownerUserId, int batchSize) {
        return jdbcTemplate.query(
                "SELECT BIN_TO_UUID(id),object_key_cipher FROM audio_object "
                        + "WHERE owner_user_id=UUID_TO_BIN(?) AND status<>'DELETED' "
                        + "ORDER BY created_at LIMIT ?",
                (resultSet, rowNumber) -> new AudioRow(
                        UUID.fromString(resultSet.getString(1)), resultSet.getBytes(2)),
                ownerUserId.toString(),
                batchSize);
    }

    private void markAudioDeleted(UUID ownerUserId, UUID audioObjectId, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        jdbcTemplate.update(
                "UPDATE audio_object SET status='DELETED',storage_version=NULL,deleted_at=?,"
                        + "updated_at=?,version=version+1 WHERE id=UUID_TO_BIN(?) "
                        + "AND owner_user_id=UUID_TO_BIN(?) AND status<>'DELETED'",
                timestamp,
                timestamp,
                audioObjectId.toString(),
                ownerUserId.toString());
    }

    private int cleanupDatabasePhase(UUID ownerUserId, int batchSize) {
        // 注销受理已在app_user锁内置DELETING，旧图谱发布无法再通过ACTIVE复验。
        // 按外键顺序分批清理；无论知识开关是否关闭都执行，不读取旧投影内容。
        for (String table : List.of("assistant_turn_request", "assistant_session", "knowledge_graph_edge", "knowledge_graph_node", "knowledge_graph_snapshot")) {
            int graphChanged = deleteDirectBatch(table, ownerUserId, "1=1", batchSize);
            if (graphChanged > 0) { return graphChanged; }
        }
        int changed = deleteTaskBatch(ownerUserId, batchSize);
        if (changed > 0) {
            return changed;
        }
        changed = deleteDirectBatch(
                "voice_training_dataset", ownerUserId, "1=1", batchSize);
        if (changed > 0) {
            return changed;
        }
        changed = deleteDirectBatch(
                "voice_collection_sample", ownerUserId, "1=1", batchSize);
        if (changed > 0) {
            return changed;
        }
        changed = deleteDirectBatch("audio_object", ownerUserId, "status='DELETED'", batchSize);
        if (changed > 0) {
            return changed;
        }
        changed = deleteTaskHistoryBatch(ownerUserId, batchSize);
        if (changed > 0) {
            return changed;
        }
        changed = deleteAggregateBatch(
                "CONTACT_ALIAS", "contact_alias", "owner_user_id",
                ownerUserId, batchSize);
        if (changed > 0) {
            return changed;
        }
        changed = deleteAggregateBatch(
                "CONTACT_BINDING", "contact_binding", "owner_user_id",
                ownerUserId, batchSize);
        if (changed > 0) {
            return changed;
        }
        changed = deleteDirectBatch(
                "safety_command_template", ownerUserId, "1=1", batchSize);
        if (changed > 0) {
            return changed;
        }
        changed = deleteAggregateBatch(
                "SAFETY_COMMAND_ENROLLMENT", "safety_command_enrollment",
                "owner_user_id", ownerUserId, batchSize);
        if (changed > 0) {
            return changed;
        }
        changed = deleteDirectBatch(
                "safety_command_namespace", ownerUserId, "1=1", batchSize);
        if (changed > 0) {
            return changed;
        }
        changed = deleteDirectBatch(
                "routine_command_template", ownerUserId, "1=1", batchSize);
        if (changed > 0) {
            return changed;
        }
        changed = deleteDirectBatch(
                "routine_command_deletion", ownerUserId, "1=1", batchSize);
        if (changed > 0) {
            return changed;
        }
        changed = deleteDirectBatch(
                "routine_command_namespace", ownerUserId, "1=1", batchSize);
        if (changed > 0) {
            return changed;
        }
        changed = deleteInvitationSessionBatch(ownerUserId, batchSize);
        if (changed > 0) {
            return changed;
        }
        changed = deleteDirectBatch(
                "contact_invitation", ownerUserId, "1=1", batchSize);
        if (changed > 0) {
            return changed;
        }
        changed = deleteDirectBatch(
                "personal_assistant_memory", ownerUserId, "1=1", batchSize);
        if (changed > 0) {
            return changed;
        }
        changed = deleteAggregateBatch(
                "CONSENT_RECORD", "consent_record", "user_id",
                ownerUserId, batchSize);
        if (changed > 0) {
            return changed;
        }
        changed = deleteUserIdBatch("refresh_token", ownerUserId, batchSize);
        if (changed > 0) {
            return changed;
        }
        changed = deleteUserIdBatch("token_family", ownerUserId, batchSize);
        if (changed > 0) {
            return changed;
        }
        return deleteDirectBatch("task_namespace", ownerUserId, "1=1", batchSize);
    }

    private int deleteTaskBatch(UUID ownerUserId, int batchSize) {
        String selectedIds = "SELECT id FROM (SELECT id FROM task_session "
                + "WHERE owner_user_id=UUID_TO_BIN(?) ORDER BY created_at LIMIT "
                + batchSize + ") batch";
        jdbcTemplate.update(
                "DELETE FROM routine_command_learning_outbox WHERE task_session_id IN (" + selectedIds + ")",
                ownerUserId.toString());
        jdbcTemplate.update(
                "DELETE FROM task_operation WHERE task_session_id IN (" + selectedIds + ")",
                ownerUserId.toString());
        jdbcTemplate.update(
                "DELETE FROM task_candidate WHERE task_session_id IN (" + selectedIds + ")",
                ownerUserId.toString());
        return jdbcTemplate.update(
                "DELETE FROM task_session WHERE id IN (" + selectedIds + ")",
                ownerUserId.toString());
    }

    private int deleteTaskHistoryBatch(UUID ownerUserId, int batchSize) {
        deleteOutboxForAggregateBatch(
                "TASK_HISTORY_DELETION", "task_history_deletion", "owner_user_id",
                ownerUserId, batchSize);
        return deleteDirectBatch(
                "task_history_deletion", ownerUserId, "1=1", batchSize);
    }

    private int deleteAggregateBatch(
            String aggregateType,
            String table,
            String ownerColumn,
            UUID ownerUserId,
            int batchSize) {
        deleteOutboxForAggregateBatch(
                aggregateType, table, ownerColumn, ownerUserId, batchSize);
        return deleteDirectBatch(
                table, ownerColumn, ownerUserId, "1=1", batchSize);
    }

    private void deleteOutboxForAggregateBatch(
            String aggregateType,
            String table,
            String ownerColumn,
            UUID ownerUserId,
            int batchSize) {
        requireTable(table);
        requireOwnerColumn(ownerColumn);
        String selectedIds = "SELECT id FROM (SELECT id FROM " + table
                + " WHERE " + ownerColumn + "=UUID_TO_BIN(?) ORDER BY id LIMIT "
                + batchSize
                + ") batch";
        jdbcTemplate.update(
                "DELETE FROM outbox_event WHERE aggregate_type=? AND aggregate_id IN ("
                        + selectedIds + ")",
                aggregateType,
                ownerUserId.toString());
    }

    private int deleteInvitationSessionBatch(UUID ownerUserId, int batchSize) {
        String invitationIds = "SELECT id FROM (SELECT id FROM contact_invitation "
                + "WHERE owner_user_id=UUID_TO_BIN(?) ORDER BY id LIMIT "
                + batchSize + ") batch";
        return jdbcTemplate.update(
                "DELETE FROM invitation_session WHERE invitation_id IN (" + invitationIds + ")",
                ownerUserId.toString());
    }

    private int deleteDirectBatch(
            String table,
            UUID ownerUserId,
            String additionalCondition,
            int batchSize) {
        return deleteDirectBatch(
                table, "owner_user_id", ownerUserId, additionalCondition, batchSize);
    }

    private int deleteDirectBatch(
            String table,
            String ownerColumn,
            UUID ownerUserId,
            String additionalCondition,
            int batchSize) {
        requireTable(table);
        requireOwnerColumn(ownerColumn);
        if (!List.of("1=1", "status='DELETED'").contains(additionalCondition)) {
            throw new IllegalArgumentException("不支持的账号注销删除条件");
        }
        return jdbcTemplate.update(
                "DELETE FROM " + table + " WHERE " + ownerColumn
                        + "=UUID_TO_BIN(?) AND "
                        + additionalCondition + " LIMIT " + batchSize,
                ownerUserId.toString());
    }

    private int deleteUserIdBatch(String table, UUID ownerUserId, int batchSize) {
        if (!List.of("refresh_token", "token_family").contains(table)) {
            throw new IllegalArgumentException("不支持的账号注销令牌表");
        }
        return jdbcTemplate.update(
                "DELETE FROM " + table + " WHERE user_id=UUID_TO_BIN(?) LIMIT " + batchSize,
                ownerUserId.toString());
    }

    private boolean allBusinessRowsCleared(UUID ownerUserId) {
        String owner = ownerUserId.toString();
        return count("audio_object", "owner_user_id", owner) == 0L
                && count("assistant_turn_request", "owner_user_id", owner) == 0L
                && count("assistant_session", "owner_user_id", owner) == 0L
                && count("knowledge_graph_edge", "owner_user_id", owner) == 0L
                && count("knowledge_graph_node", "owner_user_id", owner) == 0L
                && count("knowledge_graph_snapshot", "owner_user_id", owner) == 0L
                && count("voice_training_dataset", "owner_user_id", owner) == 0L
                && count("voice_collection_sample", "owner_user_id", owner) == 0L
                && count("contact_alias", "owner_user_id", owner) == 0L
                && count("contact_binding", "owner_user_id", owner) == 0L
                && count("contact_invitation", "owner_user_id", owner) == 0L
                && countInvitationSessions(owner) == 0L
                && count("personal_assistant_memory", "owner_user_id", owner) == 0L
                && count("consent_record", "user_id", owner) == 0L
                && count("refresh_token", "user_id", owner) == 0L
                && count("token_family", "user_id", owner) == 0L
                && count("safety_command_enrollment", "owner_user_id", owner) == 0L
                && count("routine_command_learning_outbox", "owner_user_id", owner) == 0L
                && count("safety_command_namespace", "owner_user_id", owner) == 0L
                && count("safety_command_template", "owner_user_id", owner) == 0L
                && count("routine_command_deletion", "owner_user_id", owner) == 0L
                && count("routine_command_namespace", "owner_user_id", owner) == 0L
                && count("routine_command_template", "owner_user_id", owner) == 0L
                && count("task_candidate", "owner_user_id", owner) == 0L
                && count("task_history_deletion", "owner_user_id", owner) == 0L
                && count("task_namespace", "owner_user_id", owner) == 0L
                && count("task_operation", "owner_user_id", owner) == 0L
                && count("task_session", "owner_user_id", owner) == 0L;
    }

    private long count(String table, String ownerColumn, String owner) {
        requireTable(table);
        requireOwnerColumn(ownerColumn);
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + ownerColumn + "=UUID_TO_BIN(?)",
                Long.class,
                owner);
        return value == null ? -1L : value;
    }

    private long countInvitationSessions(String owner) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invitation_session session "
                        + "JOIN contact_invitation invitation ON invitation.id=session.invitation_id "
                        + "WHERE invitation.owner_user_id=UUID_TO_BIN(?)",
                Long.class,
                owner);
        return value == null ? -1L : value;
    }

    private void appendCompletionEvent(UUID jobId, Instant completedAt) {
        Timestamp timestamp = Timestamp.from(completedAt);
        jdbcTemplate.update(
                "INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,payload_json,"
                        + "status,available_at,created_at) VALUES(UUID_TO_BIN(?),"
                        + "'ACCOUNT_CLOSURE',UUID_TO_BIN(?),'ACCOUNT_CLOSURE_COMPLETED',"
                        + "JSON_OBJECT('completedAt',?),'PENDING',?,?)",
                UUID.randomUUID().toString(),
                jobId.toString(),
                completedAt.toString(),
                timestamp,
                timestamp);
    }

    private void appendTombstoneEvent(UUID tombstoneId, long accountGeneration, Instant createdAt) {
        Timestamp timestamp = Timestamp.from(createdAt);
        jdbcTemplate.update(
                "INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,payload_json,"
                        + "status,available_at,created_at) VALUES(UUID_TO_BIN(?),"
                        + "'DELETION_TOMBSTONE',UUID_TO_BIN(?),'DELETION_TOMBSTONE_CREATED',"
                        + "JSON_OBJECT('accountGeneration',?,'policyVersion',?,"
                        + "'completedAt',?),'PENDING',?,?)",
                UUID.randomUUID().toString(),
                tombstoneId.toString(),
                accountGeneration,
                TOMBSTONE_POLICY_VERSION,
                createdAt.toString(),
                timestamp,
                timestamp);
    }

    private void requireSubjectHash(byte[] subjectHash) {
        if (subjectHash == null || subjectHash.length != 32) {
            throw new IllegalStateException("注销账号缺少有效主体摘要");
        }
    }

    private int requireBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("账号注销清理批次必须在 1 至 100 之间");
        }
        return batchSize;
    }
    private void requireTable(String table) {
        if (!List.of(
                "audio_object",
                "contact_alias",
                "contact_binding",
                "contact_invitation",
                "consent_record",
                "personal_assistant_memory",
                "assistant_turn_request",
                "assistant_session",
                "knowledge_graph_edge",
                "knowledge_graph_node",
                "knowledge_graph_snapshot",
                "refresh_token",
                "routine_command_deletion",
                "routine_command_learning_outbox",
                "routine_command_namespace",
                "routine_command_template",
                "safety_command_enrollment",
                "safety_command_namespace",
                "safety_command_template",
                "task_candidate",
                "task_history_deletion",
                "task_namespace",
                "task_operation",
                "task_session",
                "token_family",
                "voice_training_dataset",
                "voice_collection_sample").contains(table)) {
            throw new IllegalArgumentException("不支持的账号注销数据表");
        }
    }

    private void requireOwnerColumn(String ownerColumn) {
        if (!List.of("owner_user_id", "user_id").contains(ownerColumn)) {
            throw new IllegalArgumentException("不支持的账号注销 owner 列");
        }
    }

    private record AudioRow(UUID id, byte[] objectKeyCipher) {
    }

    private record ClosureRow(
            long accountGeneration,
            Instant acceptedAt,
            Instant reRegistrationNotBefore,
            String status) {
    }

    private record AccountRow(byte[] subjectHash, long accountGeneration, String status) {
    }
}
