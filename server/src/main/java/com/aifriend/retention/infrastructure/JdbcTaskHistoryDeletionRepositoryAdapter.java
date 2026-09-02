package com.aifriend.retention.infrastructure;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.retention.application.TaskHistoryDeletionJob;
import com.aifriend.retention.application.TaskHistoryDeletionRepositoryPort;
import com.aifriend.retention.application.TaskHistoryDeletionView;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * MySQL 任务历史清除作业与 Outbox 适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JdbcTaskHistoryDeletionRepositoryAdapter implements TaskHistoryDeletionRepositoryPort {
    private final JdbcTemplate jdbcTemplate;
    /**
     * 创建持久化适配器。
     *
     * @param jdbcTemplate owner 隔离的数据库访问组件
     */
    public JdbcTaskHistoryDeletionRepositoryAdapter(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    /** {@inheritDoc} */
    @Override @Transactional(rollbackFor = Exception.class)
    public TaskHistoryDeletionView accept(UUID ownerUserId, byte[] keyHash, byte[] requestHash, Instant now) {
        jdbcTemplate.update("INSERT IGNORE INTO task_namespace(owner_user_id,version,updated_at) VALUES(UUID_TO_BIN(?),0,?)", ownerUserId.toString(), Timestamp.from(now));
        jdbcTemplate.queryForObject("SELECT version FROM task_namespace WHERE owner_user_id=UUID_TO_BIN(?) FOR UPDATE", Long.class, ownerUserId.toString());
        List<Row> replay = query("SELECT request_hash,status,requested_at,completed_at FROM task_history_deletion WHERE owner_user_id=UUID_TO_BIN(?) AND idempotency_key_hash=?", ownerUserId, keyHash);
        if (!replay.isEmpty()) {
            if (!MessageDigest.isEqual(replay.get(0).requestHash(), requestHash)) throw new BusinessException(ErrorCode.SESSION_CONFLICT);
            return replay.get(0).view();
        }
        List<Row> active = query("SELECT request_hash,status,requested_at,completed_at FROM task_history_deletion WHERE owner_user_id=UUID_TO_BIN(?) AND status='CLEARING'", ownerUserId, null);
        if (!active.isEmpty()) return active.get(0).view();
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO task_history_deletion(id,owner_user_id,idempotency_key_hash,request_hash,status,cutoff_at,requested_at,retry_count,next_attempt_at,version,created_at,updated_at) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,'CLEARING',?,?,0,?,0,?,?)", id.toString(), ownerUserId.toString(), keyHash, requestHash, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,payload_json,status,available_at,created_at) VALUES(UUID_TO_BIN(?),'TASK_HISTORY_DELETION',UUID_TO_BIN(?),'TASK_HISTORY_DELETION_REQUESTED',JSON_OBJECT('requestedAt',?),'PENDING',?,?)", UUID.randomUUID().toString(), id.toString(), now.toString(), Timestamp.from(now), Timestamp.from(now));
        return new TaskHistoryDeletionView("CLEARING", now, null);
    }

    /** {@inheritDoc} */
    @Override public Optional<TaskHistoryDeletionView> findLatest(UUID ownerUserId) {
        List<Row> rows=query("SELECT request_hash,status,requested_at,completed_at FROM task_history_deletion WHERE owner_user_id=UUID_TO_BIN(?) ORDER BY requested_at DESC LIMIT 1",ownerUserId,null);
        return rows.stream().findFirst().map(Row::view);
    }

    /** {@inheritDoc} */
    @Override public List<TaskHistoryDeletionJob> findReady(Instant now,int batchSize) {
        return jdbcTemplate.query("SELECT BIN_TO_UUID(id),BIN_TO_UUID(owner_user_id),cutoff_at,retry_count FROM task_history_deletion WHERE status='CLEARING' AND next_attempt_at<=? ORDER BY next_attempt_at LIMIT ?", (rs,n)->new TaskHistoryDeletionJob(UUID.fromString(rs.getString(1)),UUID.fromString(rs.getString(2)),rs.getTimestamp(3).toInstant(),rs.getInt(4)),Timestamp.from(now),batchSize);
    }

    /** {@inheritDoc} */
    @Override public void markRetry(UUID jobId,Instant attemptedAt,Instant nextAttemptAt) {
        jdbcTemplate.update("UPDATE task_history_deletion SET retry_count=retry_count+1,last_attempt_at=?,next_attempt_at=?,updated_at=?,version=version+1 WHERE id=UUID_TO_BIN(?) AND status='CLEARING'",Timestamp.from(attemptedAt),Timestamp.from(nextAttemptAt),Timestamp.from(attemptedAt),jobId.toString());
    }

    /** {@inheritDoc} */
    @Override @Transactional(rollbackFor = Exception.class)
    public void markCompleted(UUID jobId,Instant now) {
        int changed=jdbcTemplate.update("UPDATE task_history_deletion SET status='COMPLETED',completed_at=?,last_attempt_at=?,updated_at=?,version=version+1 WHERE id=UUID_TO_BIN(?) AND status='CLEARING'",Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),jobId.toString());
        if(changed==1) jdbcTemplate.update("INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,payload_json,status,available_at,created_at) VALUES(UUID_TO_BIN(?),'TASK_HISTORY_DELETION',UUID_TO_BIN(?),'TASK_HISTORY_DELETION_COMPLETED',JSON_OBJECT('completedAt',?),'PENDING',?,?)",UUID.randomUUID().toString(),jobId.toString(),now.toString(),Timestamp.from(now),Timestamp.from(now));
    }

    private List<Row> query(String sql,UUID owner,byte[] key) {
        Object[] args=key==null?new Object[]{owner.toString()}:new Object[]{owner.toString(),key};
        return jdbcTemplate.query(sql,(rs,n)->new Row(rs.getBytes(1),rs.getString(2),rs.getTimestamp(3).toInstant(),rs.getTimestamp(4)==null?null:rs.getTimestamp(4).toInstant()),args);
    }
    private record Row(byte[] requestHash,String status,Instant requestedAt,Instant completedAt){ TaskHistoryDeletionView view(){return new TaskHistoryDeletionView(status,requestedAt,completedAt);} }
}
