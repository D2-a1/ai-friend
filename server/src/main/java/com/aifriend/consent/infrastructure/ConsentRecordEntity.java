package com.aifriend.consent.infrastructure;

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

import com.aifriend.consent.domain.ConsentDecision;
import com.aifriend.consent.domain.ConsentType;

import lombok.Getter;

/**
 * 追加式授权记录 JPA 实体，只读映射历史记录。
 *
 * @author Codex
 * @since 1.0.0
 */
@Entity
@Table(name = "consent_record")
@Getter
public class ConsentRecordEntity {

    /**
     * 供 JPA 反射创建只读历史实体。
     */
    protected ConsentRecordEntity() {
    }

    /** 记录 UUID。 */
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID id;

    /** 数据库全局追加顺序号，用于同毫秒内稳定判断最新决定。 */
    @Column(name = "sequence_no", nullable = false, insertable = false, updatable = false)
    private long sequenceNo;

    /** 用户 UUID。 */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID userId;

    /** 授权类型。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 40, nullable = false)
    private ConsentType type;

    /** 授权决定。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "decision", length = 20, nullable = false)
    private ConsentDecision decision;

    /** 政策版本。 */
    @Column(name = "policy_version", length = 40, nullable = false)
    private String policyVersion;

    /** 客户端明确确认时间。 */
    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;

    /** 服务端决定落库时间。 */
    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    /** 幂等键摘要。 */
    @Column(name = "idempotency_key_hash", columnDefinition = "BINARY(32)", nullable = false)
    private byte[] idempotencyKeyHash;

    /** 请求语义摘要。 */
    @Column(name = "request_hash", columnDefinition = "BINARY(32)", nullable = false)
    private byte[] requestHash;
}
