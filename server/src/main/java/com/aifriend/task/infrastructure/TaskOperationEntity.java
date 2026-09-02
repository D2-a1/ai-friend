package com.aifriend.task.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.aifriend.task.domain.TaskOperationType;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 任务写操作幂等墓碑实体。
 *
 * @author Codex
 * @since 1.0.0
 */
@Entity @Table(name = "task_operation") @Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskOperationEntity {
    /** 操作 UUID。 */
    @Id @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID id;
    /** 会话 UUID。 */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "task_session_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID taskSessionId;
    /** owner UUID。 */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "owner_user_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID ownerUserId;
    /** 写操作类型。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", length = 20, nullable = false)
    private TaskOperationType operationType;
    /** 幂等键摘要。 */
    @Column(name = "idempotency_key_hash", columnDefinition = "BINARY(32)", nullable = false)
    private byte[] idempotencyKeyHash;
    /** 请求摘要。 */
    @Column(name = "request_hash", columnDefinition = "BINARY(32)", nullable = false)
    private byte[] requestHash;
    /** 操作结果会话版本。 */
    @Column(name = "resulting_session_version", nullable = false)
    private long resultingSessionVersion;
    /** 完成时间。 */
    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    /**
     * 创建任务写操作墓碑。
     *
     * @param id 操作 UUID
     * @param taskSessionId 会话 UUID
     * @param ownerUserId owner UUID
     * @param operationType 操作类型
     * @param idempotencyKeyHash 幂等摘要
     * @param requestHash 请求摘要
     * @param resultingSessionVersion 结果版本
     * @param completedAt 完成时间
     */
    public TaskOperationEntity(UUID id, UUID taskSessionId, UUID ownerUserId,
            TaskOperationType operationType, byte[] idempotencyKeyHash,
            byte[] requestHash, long resultingSessionVersion, Instant completedAt) {
        this.id = id; this.taskSessionId = taskSessionId;
        this.ownerUserId = ownerUserId; this.operationType = operationType;
        this.idempotencyKeyHash = idempotencyKeyHash.clone();
        this.requestHash = requestHash.clone();
        this.resultingSessionVersion = resultingSessionVersion;
        this.completedAt = completedAt;
    }

    /**
     * 获取幂等摘要副本。
     *
     * @return 幂等摘要副本
     */
    public byte[] getIdempotencyKeyHash() { return idempotencyKeyHash.clone(); }
    /**
     * 获取请求摘要副本。
     *
     * @return 请求摘要副本
     */
    public byte[] getRequestHash() { return requestHash.clone(); }
}
