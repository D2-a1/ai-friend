package com.aifriend.retention.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifriend.retention.application.TaskHistoryStoragePort;
import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.voice.application.AudioObjectStoragePort;

/**
 * MySQL 与私有 TASK 音频对象的有界清除适配器。
 *
 * <p>对象存储删除在数据库事务外执行，成功后才用短事务清除数据库引用，避免把外部 I/O
 * 放进数据库事务。对象存储或数据库任一步失败都会保留作业的 {@code CLEARING} 状态。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JdbcTaskHistoryStorageAdapter implements TaskHistoryStoragePort {
    private final JdbcTemplate jdbcTemplate;
    private final AudioObjectStoragePort storagePort;
    private final SensitiveDataProtector protector;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建存储清除适配器。
     *
     * @param jdbcTemplate owner 隔离的数据库访问组件
     * @param storagePort 私有音频对象存储端口
     * @param protector 对象键解密组件
     * @param transactionManager 数据库事务管理器
     */
    public JdbcTaskHistoryStorageAdapter(JdbcTemplate jdbcTemplate,
            AudioObjectStoragePort storagePort,
            SensitiveDataProtector protector,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.storagePort = storagePort;
        this.protector = protector;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** {@inheritDoc} */
    @Override
    public int cleanupBatch(UUID owner, Instant cutoff, int batchSize) {
        List<AudioRow> audio = jdbcTemplate.query(
                "SELECT BIN_TO_UUID(id),object_key_cipher FROM audio_object "
                        + "WHERE owner_user_id=UUID_TO_BIN(?) AND purpose='TASK' AND created_at<=? "
                        + "AND status<>'DELETED' ORDER BY created_at LIMIT ?",
                (rs, rowNum) -> new AudioRow(UUID.fromString(rs.getString(1)), rs.getBytes(2)),
                owner.toString(), Timestamp.from(cutoff), batchSize);
        for (AudioRow row : audio) {
            storagePort.delete(protector.decrypt(row.keyCipher()));
            transactionTemplate.executeWithoutResult(status ->
                    markAudioDeleted(owner, row.id(), cutoff, Instant.now()));
        }
        int remaining = Math.max(0, batchSize - audio.size());
        int deletedTasks = remaining == 0 ? 0 : transactionTemplate.execute(status ->
                deleteTaskBatch(owner, cutoff, remaining));
        return audio.size() + deletedTasks;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isCleared(UUID owner, Instant cutoff) {
        Long audio = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audio_object WHERE owner_user_id=UUID_TO_BIN(?) "
                        + "AND purpose='TASK' AND created_at<=? AND status<>'DELETED'",
                Long.class, owner.toString(), Timestamp.from(cutoff));
        Long tasks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_session WHERE owner_user_id=UUID_TO_BIN(?) AND created_at<=?",
                Long.class, owner.toString(), Timestamp.from(cutoff));
        Long learning = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM routine_command_learning_outbox "
                        + "WHERE owner_user_id=UUID_TO_BIN(?) AND created_at<=?",
                Long.class, owner.toString(), Timestamp.from(cutoff));
        return audio != null && audio == 0L && tasks != null && tasks == 0L
                && learning != null && learning == 0L;
    }

    private void markAudioDeleted(UUID owner, UUID id, Instant cutoff, Instant now) {
        jdbcTemplate.update("UPDATE audio_object SET status='DELETED',storage_version=NULL,deleted_at=?,"
                        + "updated_at=?,version=version+1 WHERE id=UUID_TO_BIN(?) "
                        + "AND owner_user_id=UUID_TO_BIN(?) AND purpose='TASK' AND created_at<=?",
                Timestamp.from(now), Timestamp.from(now), id.toString(), owner.toString(),
                Timestamp.from(cutoff));
    }

    private int deleteTaskBatch(UUID owner, Instant cutoff, int limit) {
        String ids = "SELECT id FROM (SELECT id FROM task_session WHERE owner_user_id=UUID_TO_BIN(?) "
                + "AND created_at<=? ORDER BY created_at LIMIT " + limit + ") batch";
        jdbcTemplate.update("DELETE FROM routine_command_learning_outbox "
                        + "WHERE task_session_id IN (" + ids + ")",
                owner.toString(), Timestamp.from(cutoff));
        jdbcTemplate.update("DELETE FROM task_operation WHERE task_session_id IN (" + ids + ")",
                owner.toString(), Timestamp.from(cutoff));
        jdbcTemplate.update("DELETE FROM task_candidate WHERE task_session_id IN (" + ids + ")",
                owner.toString(), Timestamp.from(cutoff));
        return jdbcTemplate.update("DELETE FROM task_session WHERE id IN (" + ids + ")",
                owner.toString(), Timestamp.from(cutoff));
    }

    private record AudioRow(UUID id, byte[] keyCipher) {
    }
}
