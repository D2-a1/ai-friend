package com.aifriend.retention.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aifriend.retention.application.DeletionTombstoneExportCandidate;
import com.aifriend.retention.application.DeletionTombstoneExportRecord;
import com.aifriend.retention.application.DeletionTombstoneExportRepositoryPort;
import com.aifriend.retention.application.EncryptedDeletionTombstoneEnvelope;

/**
 * MySQL 删除墓碑灾备导出状态适配器。
 *
 * <p>候选读取不持有外部 I/O 期间的数据库锁；准备、确认和失败退避均使用单条条件更新，
 * 保证固定密文包可安全重放且迟到结果不能覆盖已确认状态。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JdbcDeletionTombstoneExportRepositoryAdapter
        implements DeletionTombstoneExportRepositoryPort {

    /** 参数化数据库访问组件。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建删除墓碑导出状态适配器。
     *
     * @param jdbcTemplate 参数化数据库访问组件
     */
    public JdbcDeletionTombstoneExportRepositoryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public List<DeletionTombstoneExportCandidate> listReady(Instant now, int batchSize) {
        int safeBatchSize = requireBatchSize(batchSize);
        return jdbcTemplate.query(
                "SELECT BIN_TO_UUID(id) tombstone_id,subject_hash,old_account_generation,"
                        + "accepted_at,completed_at,re_registration_not_before,policy_version,"
                        + "replay_until,export_key_id,export_payload_cipher,export_payload_hash,"
                        + "export_retry_count FROM deletion_tombstone "
                        + "WHERE disaster_recovery_exported_at IS NULL "
                        + "AND export_next_attempt_at<=? ORDER BY export_next_attempt_at,id LIMIT ?",
                (resultSet, rowNumber) -> {
                    DeletionTombstoneExportRecord record = new DeletionTombstoneExportRecord(
                            UUID.fromString(resultSet.getString("tombstone_id")),
                            resultSet.getBytes("subject_hash"),
                            resultSet.getLong("old_account_generation"),
                            resultSet.getTimestamp("accepted_at").toInstant(),
                            resultSet.getTimestamp("completed_at").toInstant(),
                            resultSet.getTimestamp("re_registration_not_before").toInstant(),
                            resultSet.getString("policy_version"),
                            resultSet.getTimestamp("replay_until").toInstant());
                    return new DeletionTombstoneExportCandidate(
                            record,
                            toPreparedEnvelope(
                                    resultSet.getString("export_key_id"),
                                    resultSet.getBytes("export_payload_cipher"),
                                    resultSet.getBytes("export_payload_hash")),
                            resultSet.getInt("export_retry_count"));
                },
                Timestamp.from(now),
                safeBatchSize);
    }

    /** {@inheritDoc} */
    @Override
    public boolean prepare(
            UUID tombstoneId,
            EncryptedDeletionTombstoneEnvelope envelope,
            Instant now) {
        int changed = jdbcTemplate.update(
                "UPDATE deletion_tombstone SET export_key_id=?,export_payload_cipher=?,"
                        + "export_payload_hash=?,export_last_attempt_at=?,export_next_attempt_at=? "
                        + "WHERE id=UUID_TO_BIN(?) AND disaster_recovery_exported_at IS NULL "
                        + "AND export_key_id IS NULL AND export_payload_cipher IS NULL "
                        + "AND export_payload_hash IS NULL",
                envelope.keyId(),
                envelope.encryptedEnvelope(),
                envelope.envelopeHash(),
                Timestamp.from(now),
                Timestamp.from(now),
                tombstoneId.toString());
        return changed == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean markExported(
            UUID tombstoneId,
            byte[] expectedEnvelopeHash,
            Instant exportedAt,
            byte[] receiptHash) {
        requireHash(expectedEnvelopeHash);
        requireHash(receiptHash);
        Timestamp timestamp = Timestamp.from(exportedAt);
        int changed = jdbcTemplate.update(
                "UPDATE deletion_tombstone SET disaster_recovery_exported_at=?,"
                        + "export_receipt_hash=?,export_payload_cipher=NULL,"
                        + "export_last_attempt_at=?,export_next_attempt_at=? "
                        + "WHERE id=UUID_TO_BIN(?) AND disaster_recovery_exported_at IS NULL "
                        + "AND export_payload_cipher IS NOT NULL AND export_payload_hash=?",
                timestamp,
                receiptHash,
                timestamp,
                timestamp,
                tombstoneId.toString(),
                expectedEnvelopeHash);
        return changed == 1;
    }

    /** {@inheritDoc} */
    @Override
    public void markRetry(
            UUID tombstoneId,
            byte[] expectedEnvelopeHash,
            Instant attemptedAt,
            Instant nextAttemptAt) {
        requireHash(expectedEnvelopeHash);
        if (!nextAttemptAt.isAfter(attemptedAt)) {
            throw new IllegalArgumentException("灾备导出下一重试时间必须晚于本次尝试");
        }
        jdbcTemplate.update(
                "UPDATE deletion_tombstone SET export_retry_count=export_retry_count+1,"
                        + "export_last_attempt_at=?,export_next_attempt_at=? "
                        + "WHERE id=UUID_TO_BIN(?) AND disaster_recovery_exported_at IS NULL "
                        + "AND export_payload_hash=?",
                Timestamp.from(attemptedAt),
                Timestamp.from(nextAttemptAt),
                tombstoneId.toString(),
                expectedEnvelopeHash);
    }

    private EncryptedDeletionTombstoneEnvelope toPreparedEnvelope(
            String keyId,
            byte[] encryptedEnvelope,
            byte[] envelopeHash) {
        if (keyId == null && encryptedEnvelope == null && envelopeHash == null) {
            return null;
        }
        if (keyId == null || encryptedEnvelope == null || envelopeHash == null) {
            throw new IllegalStateException("删除墓碑固定导出包状态不完整");
        }
        return new EncryptedDeletionTombstoneEnvelope(
                keyId, encryptedEnvelope, envelopeHash);
    }

    private int requireBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("删除墓碑导出批次必须在 1 至 100 之间");
        }
        return batchSize;
    }

    private void requireHash(byte[] value) {
        if (value == null || value.length != 32) {
            throw new IllegalArgumentException("删除墓碑导出摘要必须为 32 字节");
        }
    }
}
