package com.aifriend.template.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aifriend.task.domain.TaskIntent;
import com.aifriend.template.application.RoutineCommandLearningJob;
import com.aifriend.template.application.RoutineCommandLearningQueuePort;
import com.aifriend.template.application.RoutineCommandLearningRequest;

/**
 * MySQL 日常指令学习 Outbox、租约和删除作废适配器。
 *
 * <p>领取使用行锁和跳锁语义；所有状态更新均同时校验任务 UUID 与随机租约 UUID。
 * SQL 不读取或记录联系人、转写、消息正文和原始音频。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class RoutineCommandLearningJdbcAdapter
        implements RoutineCommandLearningQueuePort {

    private static final int MAXIMUM_ATTEMPTS = 8;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建日常指令学习 Outbox 适配器。
     *
     * @param jdbcTemplate 参数化数据库访问组件
     */
    public RoutineCommandLearningJdbcAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public void enqueue(
            RoutineCommandLearningRequest request,
            long namespaceVersionAtEnqueue,
            Instant now) {
        int changed = jdbcTemplate.update("""
                INSERT INTO routine_command_learning_outbox(
                    id,task_session_id,owner_user_id,audio_object_id,intent,
                    action_start_ms,action_end_ms,dialect_code,
                    dialect_package_version,template_model_version,threshold_version,
                    namespace_version_at_enqueue,state,attempts,available_at,
                    source_retention_until,created_at,updated_at)
                SELECT UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),ao.id,?,?,?,?,?,?,?,
                    ?,'PENDING',0,?,ao.retention_until,?,?
                FROM audio_object ao
                WHERE ao.id=UUID_TO_BIN(?) AND ao.owner_user_id=UUID_TO_BIN(?)
                    AND ao.purpose='TASK' AND ao.status='CONSUMED'
                    AND ao.deleted_at IS NULL AND ao.retention_until>?
                """,
                UUID.randomUUID().toString(),
                request.taskSessionId().toString(),
                request.ownerUserId().toString(),
                request.intent().name(),
                request.actionStartMs(),
                request.actionEndMs(),
                request.dialectCode(),
                request.dialectPackageVersion(),
                request.templateModelVersion(),
                request.thresholdVersion(),
                namespaceVersionAtEnqueue,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                request.audioObjectId().toString(),
                request.ownerUserId().toString(),
                Timestamp.from(now));
        if (changed != 1) {
            throw new IllegalStateException("日常指令学习任务入队失败");
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<RoutineCommandLearningJob> claimReady(
            Instant now,
            Instant leaseUntil,
            int limit) {
        if (limit < 1 || limit > 20 || !leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("日常指令学习领取参数无效");
        }
        List<RoutineCommandLearningJob> candidates = jdbcTemplate.query("""
                SELECT BIN_TO_UUID(id) id,BIN_TO_UUID(task_session_id) task_session_id,
                    BIN_TO_UUID(owner_user_id) owner_user_id,
                    BIN_TO_UUID(audio_object_id) audio_object_id,intent,
                    action_start_ms,action_end_ms,dialect_code,dialect_package_version,
                    template_model_version,threshold_version,attempts,
                    source_retention_until,created_at
                FROM routine_command_learning_outbox
                WHERE attempts<? AND available_at<=?
                    AND (state IN ('PENDING','RETRY')
                        OR (state='PROCESSING' AND lease_until<=?))
                ORDER BY available_at,created_at,id
                LIMIT ? FOR UPDATE SKIP LOCKED
                """,
                (resultSet, rowNumber) -> new RoutineCommandLearningJob(
                        UUID.fromString(resultSet.getString("id")),
                        UUID.fromString(resultSet.getString("task_session_id")),
                        UUID.fromString(resultSet.getString("owner_user_id")),
                        UUID.fromString(resultSet.getString("audio_object_id")),
                        TaskIntent.valueOf(resultSet.getString("intent")),
                        resultSet.getInt("action_start_ms"),
                        resultSet.getInt("action_end_ms"),
                        resultSet.getString("dialect_code"),
                        resultSet.getString("dialect_package_version"),
                        resultSet.getString("template_model_version"),
                        resultSet.getString("threshold_version"),
                        resultSet.getInt("attempts"),
                        null,
                        resultSet.getTimestamp("source_retention_until").toInstant(),
                        resultSet.getTimestamp("created_at").toInstant()),
                MAXIMUM_ATTEMPTS,
                Timestamp.from(now),
                Timestamp.from(now),
                limit);
        List<RoutineCommandLearningJob> claimed = new ArrayList<>(candidates.size());
        for (RoutineCommandLearningJob candidate : candidates) {
            UUID leaseToken = UUID.randomUUID();
            int changed = jdbcTemplate.update("""
                    UPDATE routine_command_learning_outbox
                    SET state='PROCESSING',attempts=attempts+1,lease_token=UUID_TO_BIN(?),
                        lease_until=?,last_error_code=NULL,updated_at=?
                    WHERE id=UUID_TO_BIN(?) AND attempts<?
                        AND (state IN ('PENDING','RETRY')
                            OR (state='PROCESSING' AND lease_until<=?))
                    """,
                    leaseToken.toString(),
                    Timestamp.from(leaseUntil),
                    Timestamp.from(now),
                    candidate.id().toString(),
                    MAXIMUM_ATTEMPTS,
                    Timestamp.from(now));
            if (changed != 1) {
                throw new IllegalStateException("日常指令学习任务领取失败");
            }
            claimed.add(copyWithLease(candidate, leaseToken));
        }
        return List.copyOf(claimed);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<RoutineCommandLearningJob> findClaimedForUpdate(
            UUID jobId,
            UUID leaseToken) {
        List<RoutineCommandLearningJob> jobs = jdbcTemplate.query("""
                SELECT BIN_TO_UUID(id) id,BIN_TO_UUID(task_session_id) task_session_id,
                    BIN_TO_UUID(owner_user_id) owner_user_id,
                    BIN_TO_UUID(audio_object_id) audio_object_id,intent,
                    action_start_ms,action_end_ms,dialect_code,dialect_package_version,
                    template_model_version,threshold_version,attempts,
                    BIN_TO_UUID(lease_token) lease_token,source_retention_until,created_at
                FROM routine_command_learning_outbox
                WHERE id=UUID_TO_BIN(?) AND lease_token=UUID_TO_BIN(?)
                    AND state='PROCESSING' FOR UPDATE
                """,
                (resultSet, rowNumber) -> mapJob(resultSet),
                jobId.toString(),
                leaseToken.toString());
        if (jobs.size() > 1) {
            throw new IllegalStateException("日常指令学习任务不唯一");
        }
        return jobs.stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public void markDone(UUID jobId, UUID leaseToken, Instant now) {
        finish(jobId, leaseToken, "DONE", null, now);
    }

    /** {@inheritDoc} */
    @Override
    public void markRetry(
            UUID jobId,
            UUID leaseToken,
            String errorCode,
            Instant availableAt,
            Instant now) {
        int changed = jdbcTemplate.update("""
                UPDATE routine_command_learning_outbox
                SET state='RETRY',available_at=?,lease_token=NULL,lease_until=NULL,
                    last_error_code=?,updated_at=?
                WHERE id=UUID_TO_BIN(?) AND lease_token=UUID_TO_BIN(?)
                    AND state='PROCESSING'
                """,
                Timestamp.from(availableAt),
                errorCode,
                Timestamp.from(now),
                jobId.toString(),
                leaseToken.toString());
        requireSingleChange(changed);
    }

    /** {@inheritDoc} */
    @Override
    public void markSkipped(
            UUID jobId,
            UUID leaseToken,
            String reasonCode,
            Instant now) {
        finish(jobId, leaseToken, "SKIPPED", reasonCode, now);
    }

    /** {@inheritDoc} */
    @Override
    public int cancelOutstandingByOwner(
            UUID ownerUserId,
            String reasonCode,
            Instant now) {
        return jdbcTemplate.update("""
                UPDATE routine_command_learning_outbox
                SET state='SKIPPED',lease_token=NULL,lease_until=NULL,
                    last_error_code=?,completed_at=?,updated_at=?
                WHERE owner_user_id=UUID_TO_BIN(?)
                    AND state IN ('PENDING','PROCESSING','RETRY')
                """,
                reasonCode,
                Timestamp.from(now),
                Timestamp.from(now),
                ownerUserId.toString());
    }

    private RoutineCommandLearningJob copyWithLease(
            RoutineCommandLearningJob source,
            UUID leaseToken) {
        return new RoutineCommandLearningJob(
                source.id(), source.taskSessionId(), source.ownerUserId(),
                source.audioObjectId(), source.intent(), source.actionStartMs(),
                source.actionEndMs(), source.dialectCode(),
                source.dialectPackageVersion(), source.templateModelVersion(),
                source.thresholdVersion(), source.attempts() + 1, leaseToken,
                source.sourceRetentionUntil(), source.createdAt());
    }

    private RoutineCommandLearningJob mapJob(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new RoutineCommandLearningJob(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("task_session_id")),
                UUID.fromString(resultSet.getString("owner_user_id")),
                UUID.fromString(resultSet.getString("audio_object_id")),
                TaskIntent.valueOf(resultSet.getString("intent")),
                resultSet.getInt("action_start_ms"),
                resultSet.getInt("action_end_ms"),
                resultSet.getString("dialect_code"),
                resultSet.getString("dialect_package_version"),
                resultSet.getString("template_model_version"),
                resultSet.getString("threshold_version"),
                resultSet.getInt("attempts"),
                UUID.fromString(resultSet.getString("lease_token")),
                resultSet.getTimestamp("source_retention_until").toInstant(),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private void finish(
            UUID jobId,
            UUID leaseToken,
            String state,
            String reasonCode,
            Instant now) {
        int changed = jdbcTemplate.update("""
                UPDATE routine_command_learning_outbox
                SET state=?,lease_token=NULL,lease_until=NULL,last_error_code=?,
                    completed_at=?,updated_at=?
                WHERE id=UUID_TO_BIN(?) AND lease_token=UUID_TO_BIN(?)
                    AND state='PROCESSING'
                """,
                state,
                reasonCode,
                Timestamp.from(now),
                Timestamp.from(now),
                jobId.toString(),
                leaseToken.toString());
        requireSingleChange(changed);
    }

    private void requireSingleChange(int changed) {
        if (changed != 1) {
            throw new IllegalStateException("日常指令学习租约已经失效");
        }
    }
}
