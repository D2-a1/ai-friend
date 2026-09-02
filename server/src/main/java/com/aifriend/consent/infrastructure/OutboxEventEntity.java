package com.aifriend.consent.infrastructure;

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
 * 授权撤回等可靠异步事件 JPA 实体。
 *
 * @author Codex
 * @since 1.0.0
 */
@Entity
@Table(name = "outbox_event")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEventEntity {

    /** 事件 UUID。 */
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID id;

    /** 聚合类型。 */
    @Column(name = "aggregate_type", length = 40, nullable = false)
    private String aggregateType;

    /** 聚合 UUID。 */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "aggregate_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID aggregateId;

    /** 事件类型。 */
    @Column(name = "event_type", length = 80, nullable = false)
    private String eventType;

    /** 不含语音、正文、微信身份或 token 的最小 JSON 负载。 */
    @Column(name = "payload_json", columnDefinition = "JSON", nullable = false)
    private String payloadJson;

    /** PENDING/PROCESSING/COMPLETED 状态。 */
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    /** 可消费时间。 */
    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * 创建待处理 Outbox 事件。
     *
     * @param id 事件 UUID
     * @param aggregateType 聚合类型
     * @param aggregateId 聚合 UUID
     * @param eventType 事件类型
     * @param payloadJson 最小 JSON 负载
     * @param createdAt 创建时间
     */
    public OutboxEventEntity(
            UUID id,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String payloadJson,
            Instant createdAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
        this.status = "PENDING";
        this.availableAt = createdAt;
        this.createdAt = createdAt;
    }
}
