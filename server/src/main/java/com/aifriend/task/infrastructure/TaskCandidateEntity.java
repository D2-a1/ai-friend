package com.aifriend.task.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 不含展示文字的任务候选关系实体。
 *
 * @author Codex
 * @since 1.0.0
 */
@Entity @Table(name = "task_candidate") @Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskCandidateEntity {
    /** 候选 UUID。 */
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
    /** 联系人 UUID。 */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "contact_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID contactId;
    /** 可靠度分档。 */
    @Column(name = "score_band", length = 20, nullable = false)
    private String scoreBand;
    /** 候选顺序。 */
    @Column(name = "rank_no", nullable = false)
    private int rank;
    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * 创建候选关系实体。
     *
     * @param id 候选 UUID
     * @param taskSessionId 会话 UUID
     * @param ownerUserId owner UUID
     * @param contactId 联系人 UUID
     * @param scoreBand 可靠度分档
     * @param rank 顺序
     * @param createdAt 创建时间
     */
    public TaskCandidateEntity(UUID id, UUID taskSessionId, UUID ownerUserId,
            UUID contactId, String scoreBand, int rank, Instant createdAt) {
        this.id = id; this.taskSessionId = taskSessionId;
        this.ownerUserId = ownerUserId; this.contactId = contactId;
        this.scoreBand = scoreBand; this.rank = rank; this.createdAt = createdAt;
    }
}
