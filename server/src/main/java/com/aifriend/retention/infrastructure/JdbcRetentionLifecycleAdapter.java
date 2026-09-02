package com.aifriend.retention.infrastructure;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import com.aifriend.retention.application.RetentionLifecyclePort;
import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.voice.application.AudioObjectStoragePort;

/**
 * MySQL 与私有音频对象存储的统一生命周期适配器。
 *
 * <p>对象存储删除不占用数据库事务；失败对象只记录退避事实。任务、音频元数据和
 * 邀请临时数据按外键顺序分别使用短事务清理，允许调度中断后安全重入。
 * 删除墓碑只有取得可信灾备导出回执且超过重放截止后才可删除。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JdbcRetentionLifecycleAdapter implements RetentionLifecyclePort {
    /** 脱敏记录生命周期音频删除失败的日志组件。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(
            JdbcRetentionLifecycleAdapter.class);
    /** 生命周期音频删除稳定阶段码。 */
    private static final String AUDIO_DELETION_FAILURE_STAGE =
            "RETENTION_AUDIO_DELETION";
    /** 参数化数据库访问组件。 */
    private final JdbcTemplate jdbcTemplate;
    /** 私有音频对象存储端口。 */
    private final AudioObjectStoragePort storagePort;
    /** 对象键解密组件。 */
    private final SensitiveDataProtector protector;
    /** 有界数据库短事务模板。 */
    private final TransactionTemplate transactionTemplate;
    /** 已确认对象不存在并完成数据库标记的计数器。 */
    private final Counter confirmedAudioDeletionCounter;
    /** 对象删除或不存在复验失败并进入退避的计数器。 */
    private final Counter retriedAudioDeletionCounter;

    /**
     * 创建统一生命周期适配器。
     *
     * @param jdbcTemplate 参数化数据库访问组件
     * @param storagePort 私有音频对象存储端口
     * @param protector 对象键解密组件
     * @param transactionManager 数据库事务管理器
     * @param meterRegistry 低维度生命周期指标注册器
     */
    public JdbcRetentionLifecycleAdapter(
            JdbcTemplate jdbcTemplate,
            AudioObjectStoragePort storagePort,
            SensitiveDataProtector protector,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.storagePort = storagePort;
        this.protector = protector;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.confirmedAudioDeletionCounter = Counter.builder(
                        "ai.friend.retention.audio.deletions")
                .description("已确认完成的音频对象物理删除次数")
                .tag("outcome", "confirmed")
                .register(meterRegistry);
        this.retriedAudioDeletionCounter = Counter.builder(
                        "ai.friend.retention.audio.deletions")
                .description("进入退避重试的音频对象物理删除次数")
                .tag("outcome", "retry")
                .register(meterRegistry);
    }

    /** {@inheritDoc} */
    @Override
    public int cleanupBatch(Instant now, Instant taskContentCutoff, int batchSize) {
        int safeBatchSize = requireBatchSize(batchSize);
        int changed = cleanupExpiredAudio(now, safeBatchSize);
        changed += executePhase(() -> deleteExpiredTasks(taskContentCutoff, safeBatchSize));
        changed += executePhase(() -> deleteExpiredAudioMetadata(now, safeBatchSize));
        changed += executePhase(() -> deleteExpiredInvitationSessions(now, safeBatchSize));
        changed += executePhase(() -> deleteExpiredInvitations(now, safeBatchSize));
        changed += executePhase(() -> deleteExpiredExportedTombstones(now, safeBatchSize));
        return changed;
    }

    private int cleanupExpiredAudio(Instant now, int batchSize) {
        List<AudioRow> rows = jdbcTemplate.query(
                "SELECT BIN_TO_UUID(id),object_key_cipher,retention_retry_count "
                        + "FROM audio_object WHERE status<>'DELETED' "
                        + "AND retention_until<=? "
                        + "AND COALESCE(retention_next_attempt_at,retention_until)<=? "
                        + "ORDER BY retention_until LIMIT ?",
                (resultSet, rowNumber) -> new AudioRow(
                        UUID.fromString(resultSet.getString(1)),
                        resultSet.getBytes(2),
                        resultSet.getInt(3)),
                Timestamp.from(now),
                Timestamp.from(now),
                batchSize);
        int deleted = 0;
        for (AudioRow row : rows) {
            try {
                storagePort.delete(protector.decrypt(row.objectKeyCipher()));
                Integer marked = transactionTemplate.execute(status ->
                        markAudioDeleted(row.id(), now));
                int markedCount = marked == null ? 0 : marked;
                if (markedCount == 1) {
                    confirmedAudioDeletionCounter.increment();
                }
                deleted += markedCount;
            } catch (RuntimeException exception) {
                Instant nextAttemptAt = nextAudioRetryAt(row.retryCount(), now);
                LOGGER.warn(
                        "可恢复后台任务失败 stage={} errorType={} retryCount={} nextAttemptAt={}",
                        AUDIO_DELETION_FAILURE_STAGE,
                        exception.getClass().getSimpleName(),
                        row.retryCount(),
                        nextAttemptAt);
                Integer marked = transactionTemplate.execute(status ->
                        markAudioRetry(row, now, nextAttemptAt));
                if (marked != null && marked == 1) {
                    retriedAudioDeletionCounter.increment();
                }
            }
        }
        return deleted;
    }

    private int markAudioDeleted(UUID audioObjectId, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        return jdbcTemplate.update(
                "UPDATE audio_object SET status='DELETED',storage_version=NULL,deleted_at=?,"
                        + "retention_last_attempt_at=?,retention_next_attempt_at=?,updated_at=?,"
                        + "version=version+1 WHERE id=UUID_TO_BIN(?) AND status<>'DELETED' "
                        + "AND retention_until<=?",
                timestamp,
                timestamp,
                timestamp,
                timestamp,
                audioObjectId.toString(),
                timestamp);
    }

    private int markAudioRetry(AudioRow row, Instant now, Instant nextAttemptAt) {
        return jdbcTemplate.update(
                "UPDATE audio_object SET retention_retry_count=retention_retry_count+1,"
                        + "retention_last_attempt_at=?,retention_next_attempt_at=?,updated_at=?,"
                        + "version=version+1 WHERE id=UUID_TO_BIN(?) AND status<>'DELETED'",
                Timestamp.from(now),
                Timestamp.from(nextAttemptAt),
                Timestamp.from(now),
                row.id().toString());
    }

    private Instant nextAudioRetryAt(int retryCount, Instant now) {
        long delaySeconds = Math.min(900L, 30L << Math.min(retryCount, 5));
        return now.plus(Duration.ofSeconds(delaySeconds));
    }

    private int deleteExpiredTasks(Instant cutoff, int batchSize) {
        String selectedIds = "SELECT id FROM (SELECT id FROM task_session "
                + "WHERE created_at<=? ORDER BY created_at LIMIT " + batchSize + ") batch";
        Timestamp timestamp = Timestamp.from(cutoff);
        jdbcTemplate.update(
                "DELETE FROM routine_command_learning_outbox WHERE task_session_id IN (" + selectedIds + ")",
                timestamp);
        jdbcTemplate.update(
                "DELETE FROM task_operation WHERE task_session_id IN (" + selectedIds + ")",
                timestamp);
        jdbcTemplate.update(
                "DELETE FROM task_candidate WHERE task_session_id IN (" + selectedIds + ")",
                timestamp);
        return jdbcTemplate.update(
                "DELETE FROM task_session WHERE id IN (" + selectedIds + ")",
                timestamp);
    }

    private int deleteExpiredAudioMetadata(Instant now, int batchSize) {
        return jdbcTemplate.update(
                "DELETE FROM audio_object WHERE status='DELETED' AND retention_until<=? "
                        + "AND NOT EXISTS (SELECT 1 FROM task_session "
                        + "WHERE task_session.source_audio_object_id=audio_object.id) "
                        + "LIMIT " + batchSize,
                Timestamp.from(now));
    }

    private int deleteExpiredInvitationSessions(Instant now, int batchSize) {
        String selectedIds = "SELECT id FROM (SELECT session.id FROM invitation_session session "
                + "JOIN contact_invitation invitation ON invitation.id=session.invitation_id "
                + "WHERE session.created_at<=? OR invitation.expires_at<=? "
                + "ORDER BY session.created_at LIMIT " + batchSize + ") batch";
        Timestamp timestamp = Timestamp.from(now.minus(Duration.ofHours(24)));
        return jdbcTemplate.update(
                "DELETE FROM invitation_session WHERE id IN (" + selectedIds + ")",
                timestamp,
                Timestamp.from(now));
    }

    private int deleteExpiredInvitations(Instant now, int batchSize) {
        return jdbcTemplate.update(
                "DELETE FROM contact_invitation WHERE expires_at<=? "
                        + "AND NOT EXISTS (SELECT 1 FROM invitation_session "
                        + "WHERE invitation_session.invitation_id=contact_invitation.id) "
                        + "LIMIT " + batchSize,
                Timestamp.from(now));
    }

    private int deleteExpiredExportedTombstones(Instant now, int batchSize) {
        return jdbcTemplate.update(
                "DELETE FROM deletion_tombstone WHERE replay_until<=? "
                        + "AND disaster_recovery_exported_at IS NOT NULL "
                        + "AND export_receipt_hash IS NOT NULL LIMIT " + batchSize,
                Timestamp.from(now));
    }

    private int executePhase(IntPhase phase) {
        Integer changed = transactionTemplate.execute(status -> phase.execute());
        return changed == null ? 0 : changed;
    }

    private int requireBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("生命周期清理批次必须在 1 至 500 之间");
        }
        return batchSize;
    }

    @FunctionalInterface
    private interface IntPhase {
        int execute();
    }

    private record AudioRow(UUID id, byte[] objectKeyCipher, int retryCount) {
    }
}
