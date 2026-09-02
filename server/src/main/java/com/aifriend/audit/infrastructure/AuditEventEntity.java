package com.aifriend.audit.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 去标识化安全审计事件 JPA 实体。
 *
 * @author Codex
 * @since 1.0.0
 */
@Entity
@Table(name = "audit_event")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditEventEntity {

    /** 审计事件 UUID。 */
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID id;

    /** 用户内部 UUID 的 SHA-256，不保存原始主体。 */
    @Column(name = "actor_id_hash", columnDefinition = "BINARY(32)", nullable = false)
    private byte[] actorIdHash;

    /** 动作码。 */
    @Column(name = "action", length = 60, nullable = false)
    private String action;

    /** 结果码。 */
    @Column(name = "result", length = 30, nullable = false)
    private String result;

    /** 不含敏感数据的原因码。 */
    @Column(name = "reason_code", length = 60)
    private String reasonCode;

    /** 事件发生时间。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 审计事件到期时间。 */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * 创建审计事件。
     *
     * @param id 事件 UUID
     * @param actorIdHash 去标识用户摘要
     * @param action 动作码
     * @param result 结果码
     * @param reasonCode 原因码
     * @param createdAt 创建时间
     * @param expiresAt 到期时间
     */
    public AuditEventEntity(
            UUID id,
            byte[] actorIdHash,
            String action,
            String result,
            String reasonCode,
            Instant createdAt,
            Instant expiresAt) {
        this.id = id;
        this.actorIdHash = actorIdHash.clone();
        this.action = action;
        this.result = result;
        this.reasonCode = reasonCode;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }
}
