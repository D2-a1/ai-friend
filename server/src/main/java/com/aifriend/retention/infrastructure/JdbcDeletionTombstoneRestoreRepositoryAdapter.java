package com.aifriend.retention.infrastructure;

import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifriend.retention.application.DeletionTombstoneExportRecord;
import com.aifriend.retention.application.DeletionTombstoneRestoreRepositoryPort;

/**
 * 使用 MySQL 短事务幂等重放删除墓碑并记录恢复验证事实的适配器。
 *
 * <p>同一 UUID 或同一主体代次只能对应完全相同的不可变墓碑事实。恢复不会自动删除疑似
 * 复活账号；任何冲突都抛出异常，由启动门禁拒绝实例上线。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JdbcDeletionTombstoneRestoreRepositoryAdapter
        implements DeletionTombstoneRestoreRepositoryPort {

    /** 参数化数据库访问组件。 */
    private final JdbcTemplate jdbcTemplate;
    /** 有界数据库短事务模板。 */
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建删除墓碑恢复数据库适配器。
     *
     * @param jdbcTemplate 参数化数据库访问组件
     * @param transactionManager 数据库事务管理器
     */
    public JdbcDeletionTombstoneRestoreRepositoryAdapter(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** {@inheritDoc} */
    @Override
    public void replayBatch(List<DeletionTombstoneExportRecord> records, Instant replayedAt) {
        requireReplayBatch(records, replayedAt);
        transactionTemplate.executeWithoutResult(status -> {
            for (DeletionTombstoneExportRecord record : records) {
                replayOne(record, replayedAt);
            }
        });
    }

    /** {@inheritDoc} */
    @Override
    public long countResurrectedAccounts() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user account "
                        + "JOIN deletion_tombstone tombstone "
                        + "ON account.wechat_open_id_hash=tombstone.subject_hash "
                        + "AND account.account_generation<=tombstone.old_account_generation "
                        + "WHERE account.status IN ('ACTIVE','DELETING')",
                Long.class);
        if (count == null || count < 0L) {
            throw new IllegalStateException("灾备恢复旧代账号核验失败");
        }
        return count;
    }

    /** {@inheritDoc} */
    @Override
    public void recordVerification(
            byte[] snapshotIdHash,
            byte[] manifestHash,
            long expectedItemCount,
            long replayedItemCount,
            byte[] sourceProofHash,
            Instant verifiedAt) {
        requireVerification(
                snapshotIdHash,
                manifestHash,
                expectedItemCount,
                replayedItemCount,
                sourceProofHash,
                verifiedAt);
        transactionTemplate.executeWithoutResult(status -> {
            List<VerificationRow> existing = jdbcTemplate.query(
                    "SELECT manifest_hash,expected_item_count,replayed_item_count,"
                            + "source_proof_hash FROM disaster_recovery_restore_verification "
                            + "WHERE snapshot_id_hash=? FOR UPDATE",
                    (resultSet, rowNumber) -> new VerificationRow(
                            resultSet.getBytes("manifest_hash"),
                            resultSet.getLong("expected_item_count"),
                            resultSet.getLong("replayed_item_count"),
                            resultSet.getBytes("source_proof_hash")),
                    snapshotIdHash);
            if (!existing.isEmpty()) {
                if (existing.size() != 1
                        || !sameVerification(
                                existing.get(0),
                                manifestHash,
                                expectedItemCount,
                                replayedItemCount,
                                sourceProofHash)) {
                    throw new IllegalStateException("灾备恢复快照验证事实冲突");
                }
                return;
            }
            Timestamp timestamp = Timestamp.from(verifiedAt);
            int changed = jdbcTemplate.update(
                    "INSERT INTO disaster_recovery_restore_verification("
                            + "id,snapshot_id_hash,manifest_hash,expected_item_count,"
                            + "replayed_item_count,source_proof_hash,verified_at,created_at) "
                            + "VALUES(UUID_TO_BIN(?),?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(),
                    snapshotIdHash,
                    manifestHash,
                    expectedItemCount,
                    replayedItemCount,
                    sourceProofHash,
                    timestamp,
                    timestamp);
            if (changed != 1) {
                throw new IllegalStateException("灾备恢复验证事实写入不完整");
            }
        });
    }

    private void replayOne(DeletionTombstoneExportRecord record, Instant replayedAt) {
        List<TombstoneRow> existing = jdbcTemplate.query(
                "SELECT BIN_TO_UUID(id) AS tombstone_id,subject_hash,old_account_generation,"
                        + "accepted_at,completed_at,re_registration_not_before,policy_version,"
                        + "replay_until FROM deletion_tombstone "
                        + "WHERE id=UUID_TO_BIN(?) OR "
                        + "(subject_hash=? AND old_account_generation=?) FOR UPDATE",
                this::mapTombstone,
                record.tombstoneId().toString(),
                record.subjectHash(),
                record.oldAccountGeneration());
        if (!existing.isEmpty()) {
            if (existing.size() != 1 || !sameTombstone(existing.get(0), record)) {
                throw new IllegalStateException("灾备恢复墓碑不可变事实冲突");
            }
            return;
        }
        Timestamp replayTimestamp = Timestamp.from(replayedAt);
        int changed = jdbcTemplate.update(
                "INSERT INTO deletion_tombstone("
                        + "id,subject_hash,old_account_generation,accepted_at,completed_at,"
                        + "re_registration_not_before,policy_version,replay_until,"
                        + "export_next_attempt_at,created_at) "
                        + "VALUES(UUID_TO_BIN(?),?,?,?,?,?,?,?,?,?)",
                record.tombstoneId().toString(),
                record.subjectHash(),
                record.oldAccountGeneration(),
                Timestamp.from(record.acceptedAt()),
                Timestamp.from(record.completedAt()),
                Timestamp.from(record.reRegistrationNotBefore()),
                record.policyVersion(),
                Timestamp.from(record.replayUntil()),
                replayTimestamp,
                replayTimestamp);
        if (changed != 1) {
            throw new IllegalStateException("灾备恢复墓碑写入不完整");
        }
    }

    private TombstoneRow mapTombstone(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TombstoneRow(
                UUID.fromString(resultSet.getString("tombstone_id")),
                resultSet.getBytes("subject_hash"),
                resultSet.getLong("old_account_generation"),
                resultSet.getTimestamp("accepted_at").toInstant(),
                resultSet.getTimestamp("completed_at").toInstant(),
                resultSet.getTimestamp("re_registration_not_before").toInstant(),
                resultSet.getString("policy_version"),
                resultSet.getTimestamp("replay_until").toInstant());
    }

    private boolean sameTombstone(
            TombstoneRow existing,
            DeletionTombstoneExportRecord expected) {
        return existing.tombstoneId().equals(expected.tombstoneId())
                && MessageDigest.isEqual(existing.subjectHash(), expected.subjectHash())
                && existing.oldAccountGeneration() == expected.oldAccountGeneration()
                && existing.acceptedAt().equals(expected.acceptedAt())
                && existing.completedAt().equals(expected.completedAt())
                && existing.reRegistrationNotBefore().equals(expected.reRegistrationNotBefore())
                && existing.policyVersion().equals(expected.policyVersion())
                && existing.replayUntil().equals(expected.replayUntil());
    }

    private boolean sameVerification(
            VerificationRow existing,
            byte[] manifestHash,
            long expectedItemCount,
            long replayedItemCount,
            byte[] sourceProofHash) {
        return MessageDigest.isEqual(existing.manifestHash(), manifestHash)
                && existing.expectedItemCount() == expectedItemCount
                && existing.replayedItemCount() == replayedItemCount
                && MessageDigest.isEqual(existing.sourceProofHash(), sourceProofHash);
    }

    private void requireReplayBatch(
            List<DeletionTombstoneExportRecord> records,
            Instant replayedAt) {
        if (records == null
                || records.isEmpty()
                || records.size() > 100
                || records.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("灾备恢复数据库批次无效");
        }
        Objects.requireNonNull(replayedAt, "灾备恢复重放时间不能为空");
    }

    private void requireVerification(
            byte[] snapshotIdHash,
            byte[] manifestHash,
            long expectedItemCount,
            long replayedItemCount,
            byte[] sourceProofHash,
            Instant verifiedAt) {
        if (!isDigest(snapshotIdHash) || !isDigest(manifestHash) || !isDigest(sourceProofHash)) {
            throw new IllegalArgumentException("灾备恢复验证摘要无效");
        }
        if (expectedItemCount < 0L
                || expectedItemCount > 1_000_000L
                || replayedItemCount != expectedItemCount) {
            throw new IllegalArgumentException("灾备恢复验证数量无效");
        }
        Objects.requireNonNull(verifiedAt, "灾备恢复验证时间不能为空");
    }

    private boolean isDigest(byte[] value) {
        return value != null && value.length == 32;
    }

    private record TombstoneRow(
            UUID tombstoneId,
            byte[] subjectHash,
            long oldAccountGeneration,
            Instant acceptedAt,
            Instant completedAt,
            Instant reRegistrationNotBefore,
            String policyVersion,
            Instant replayUntil) {

        private TombstoneRow {
            subjectHash = Arrays.copyOf(subjectHash, subjectHash.length);
        }
    }

    private record VerificationRow(
            byte[] manifestHash,
            long expectedItemCount,
            long replayedItemCount,
            byte[] sourceProofHash) {

        private VerificationRow {
            manifestHash = Arrays.copyOf(manifestHash, manifestHash.length);
            sourceProofHash = Arrays.copyOf(sourceProofHash, sourceProofHash.length);
        }
    }
}
