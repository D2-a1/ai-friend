package com.aifriend.task.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.aifriend.task.domain.TaskState;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 任务会话 JPA 实体，业务正文只保存 AES-GCM 密文。
 *
 * @author Codex
 * @since 1.0.0
 */
@Entity
@Table(name = "task_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskSessionEntity {

    /** 会话 UUID。 */
    @Id @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID id;
    /** owner UUID。 */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "owner_user_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID ownerUserId;
    /** TASK 音频对象 UUID。 */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "source_audio_object_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID sourceAudioObjectId;
    /** 客户端任务编号。 */
    @Column(name = "client_task_id", length = 64, nullable = false)
    private String clientTaskId;
    /** 创建幂等键摘要。 */
    @Column(name = "create_idempotency_key_hash", columnDefinition = "BINARY(32)", nullable = false)
    private byte[] createIdempotencyKeyHash;
    /** 创建请求摘要。 */
    @Column(name = "create_request_hash", columnDefinition = "BINARY(32)", nullable = false)
    private byte[] createRequestHash;
    /** 当前状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 30, nullable = false)
    private TaskState state;
    /** 任务敏感载荷 AES-GCM 密文。 */
    @Lob @Column(name = "payload_cipher", columnDefinition = "MEDIUMBLOB", nullable = false)
    private byte[] payloadCipher;
    /** 当前唯一联系人 UUID，可空。 */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "selected_contact_id", columnDefinition = "BINARY(16)")
    private UUID selectedContactId;
    /** 当前复述摘要哈希，可空。 */
    @Column(name = "summary_hash", length = 100)
    private String summaryHash;
    /** 当前动作计划编号，可空。 */
    @Column(name = "plan_id", length = 64)
    private String planId;
    /** 当前动作计划过期时间，可空。 */
    @Column(name = "plan_expires_at")
    private Instant planExpiresAt;
    /** 对外会话版本。 */
    @Column(name = "session_version", nullable = false)
    private long sessionVersion;
    /** 会话过期时间。 */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    /** 更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 创建任务会话实体。
     *
     * @param id 会话 UUID
     * @param ownerUserId owner UUID
     * @param sourceAudioObjectId 音频 UUID
     * @param clientTaskId 客户端任务编号
     * @param createIdempotencyKeyHash 创建幂等摘要
     * @param createRequestHash 创建请求摘要
     * @param state 状态
     * @param payloadCipher 敏感载荷密文
     * @param selectedContactId 联系人 UUID，可空
     * @param summaryHash 复述摘要，可空
     * @param planId 计划编号，可空
     * @param planExpiresAt 计划过期时间，可空
     * @param sessionVersion 会话版本
     * @param expiresAt 会话过期时间
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public TaskSessionEntity(
            UUID id, UUID ownerUserId, UUID sourceAudioObjectId,
            String clientTaskId, byte[] createIdempotencyKeyHash,
            byte[] createRequestHash, TaskState state, byte[] payloadCipher,
            UUID selectedContactId, String summaryHash, String planId,
            Instant planExpiresAt, long sessionVersion, Instant expiresAt,
            Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.ownerUserId = ownerUserId;
        this.sourceAudioObjectId = sourceAudioObjectId;
        this.clientTaskId = clientTaskId;
        this.createIdempotencyKeyHash = createIdempotencyKeyHash.clone();
        this.createRequestHash = createRequestHash.clone();
        this.state = state;
        this.payloadCipher = payloadCipher.clone();
        this.selectedContactId = selectedContactId;
        this.summaryHash = summaryHash;
        this.planId = planId;
        this.planExpiresAt = planExpiresAt;
        this.sessionVersion = sessionVersion;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 获取创建幂等摘要副本。
     *
     * @return 创建幂等摘要副本
     */
    public byte[] getCreateIdempotencyKeyHash() { return createIdempotencyKeyHash.clone(); }
    /**
     * 获取创建请求摘要副本。
     *
     * @return 创建请求摘要副本
     */
    public byte[] getCreateRequestHash() { return createRequestHash.clone(); }
    /**
     * 获取敏感载荷密文副本。
     *
     * @return 敏感载荷密文副本
     */
    public byte[] getPayloadCipher() { return payloadCipher.clone(); }
}
