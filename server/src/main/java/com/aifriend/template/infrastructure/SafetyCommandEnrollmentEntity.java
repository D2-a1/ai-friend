package com.aifriend.template.infrastructure;

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
 * 安全指令整批注册幂等墓碑 JPA 实体。
 *
 * @author Codex
 * @since 1.0.0
 */
@Entity
@Table(name = "safety_command_enrollment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SafetyCommandEnrollmentEntity {

    /** 注册批次 UUID。 */
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID id;

    /** owner UUID。 */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "owner_user_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID ownerUserId;

    /** 幂等键 SHA-256 摘要。 */
    @Column(name = "idempotency_key_hash", columnDefinition = "BINARY(32)", nullable = false)
    private byte[] idempotencyKeyHash;

    /** 请求语义 SHA-256 摘要。 */
    @Column(name = "request_hash", columnDefinition = "BINARY(32)", nullable = false)
    private byte[] requestHash;

    /** 精确匹配的语音模板政策版本。 */
    @Column(name = "consent_policy_version", length = 40, nullable = false)
    private String consentPolicyVersion;

    /** 整批完成时间。 */
    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    /**
     * 创建安全指令注册幂等实体。
     *
     * @param id 批次 UUID
     * @param ownerUserId owner UUID
     * @param idempotencyKeyHash 幂等键摘要
     * @param requestHash 请求语义摘要
     * @param consentPolicyVersion 语音模板政策版本
     * @param completedAt 完成时间
     */
    public SafetyCommandEnrollmentEntity(
            UUID id,
            UUID ownerUserId,
            byte[] idempotencyKeyHash,
            byte[] requestHash,
            String consentPolicyVersion,
            Instant completedAt) {
        this.id = id;
        this.ownerUserId = ownerUserId;
        this.idempotencyKeyHash = idempotencyKeyHash.clone();
        this.requestHash = requestHash.clone();
        this.consentPolicyVersion = consentPolicyVersion;
        this.completedAt = completedAt;
    }

    /**
     * 获取幂等键摘要的防御性副本。
     *
     * @return 幂等键摘要副本
     */
    public byte[] getIdempotencyKeyHash() {
        return idempotencyKeyHash.clone();
    }

    /**
     * 获取请求语义摘要的防御性副本。
     *
     * @return 请求语义摘要副本
     */
    public byte[] getRequestHash() {
        return requestHash.clone();
    }
}
