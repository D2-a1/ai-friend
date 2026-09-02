package com.aifriend.template.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import com.aifriend.template.domain.SafetyCommandTemplateStatus;
import com.aifriend.template.domain.SafetyCommandType;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 安全指令声学模板 JPA 实体，只保存 AES-GCM 密文。
 *
 * @author Codex
 * @since 1.0.0
 */
@Entity
@Table(name = "safety_command_template")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SafetyCommandTemplateEntity {

    /** 模板 UUID。 */
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID id;

    /** 整批注册 UUID。 */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "enrollment_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID enrollmentId;

    /** owner UUID。 */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "owner_user_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID ownerUserId;

    /** 固定安全指令类型。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "command_type", length = 30, nullable = false)
    private SafetyCommandType commandType;

    /** 方言代码。 */
    @Column(name = "dialect_code", length = 40, nullable = false)
    private String dialectCode;

    /** 方言包版本。 */
    @Column(name = "dialect_package_version", length = 60, nullable = false)
    private String dialectPackageVersion;

    /** 声学模板模型版本。 */
    @Column(name = "template_model_version", length = 60, nullable = false)
    private String templateModelVersion;

    /** 注册区分阈值版本。 */
    @Column(name = "threshold_version", length = 60, nullable = false)
    private String thresholdVersion;

    /** AES-GCM 加密的发音内容模板，替换后为空。 */
    @Lob
    @Column(name = "template_cipher", columnDefinition = "MEDIUMBLOB")
    private byte[] templateCipher;

    /** 模板完整性摘要，替换后为空。 */
    @Column(name = "template_digest", columnDefinition = "BINARY(32)")
    private byte[] templateDigest;

    /** 生命周期状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private SafetyCommandTemplateStatus status;

    /** MySQL 生成的有效唯一占位；REPLACED 时为空。 */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "active_slot", insertable = false, updatable = false)
    private Byte activeSlot;

    /** 乐观锁版本。 */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 替换时间，有效时为空。 */
    @Column(name = "replaced_at")
    private Instant replacedAt;

    /**
     * 创建安全指令声学模板实体。
     *
     * @param id 模板 UUID
     * @param enrollmentId 注册批次 UUID
     * @param ownerUserId owner UUID
     * @param commandType 指令类型
     * @param dialectCode 方言代码
     * @param dialectPackageVersion 方言包版本
     * @param templateModelVersion 模板模型版本
     * @param thresholdVersion 阈值版本
     * @param templateCipher 模板密文，可空
     * @param templateDigest 模板摘要，可空
     * @param status 生命周期状态
     * @param version 乐观锁版本
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     * @param replacedAt 替换时间，可空
     */
    public SafetyCommandTemplateEntity(
            UUID id,
            UUID enrollmentId,
            UUID ownerUserId,
            SafetyCommandType commandType,
            String dialectCode,
            String dialectPackageVersion,
            String templateModelVersion,
            String thresholdVersion,
            byte[] templateCipher,
            byte[] templateDigest,
            SafetyCommandTemplateStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Instant replacedAt) {
        this.id = id;
        this.enrollmentId = enrollmentId;
        this.ownerUserId = ownerUserId;
        this.commandType = commandType;
        this.dialectCode = dialectCode;
        this.dialectPackageVersion = dialectPackageVersion;
        this.templateModelVersion = templateModelVersion;
        this.thresholdVersion = thresholdVersion;
        this.templateCipher = cloneNullable(templateCipher);
        this.templateDigest = cloneNullable(templateDigest);
        this.status = status;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.replacedAt = replacedAt;
    }

    /**
     * 获取声学模板密文的防御性副本。
     *
     * @return 模板密文副本，可空
     */
    public byte[] getTemplateCipher() {
        return cloneNullable(templateCipher);
    }

    /**
     * 获取模板完整性摘要的防御性副本。
     *
     * @return 模板摘要副本，可空
     */
    public byte[] getTemplateDigest() {
        return cloneNullable(templateDigest);
    }

    private static byte[] cloneNullable(byte[] value) {
        return value == null ? null : value.clone();
    }
}
