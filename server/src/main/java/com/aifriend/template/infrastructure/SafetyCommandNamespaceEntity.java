package com.aifriend.template.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * owner 安全指令命名空间锁 JPA 实体。
 *
 * @author Codex
 * @since 1.0.0
 */
@Entity
@Table(name = "safety_command_namespace")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SafetyCommandNamespaceEntity {

    /** owner UUID，同时是命名空间主键。 */
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "owner_user_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID ownerUserId;

    /** 整批替换版本，兼作 JPA 乐观锁。 */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** 最后一次整批替换或创建时间。 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 创建 owner 安全指令命名空间。
     *
     * @param ownerUserId owner UUID
     * @param version 当前版本
     * @param updatedAt 更新时间
     */
    public SafetyCommandNamespaceEntity(
            UUID ownerUserId,
            long version,
            Instant updatedAt) {
        this.ownerUserId = ownerUserId;
        this.version = version;
        this.updatedAt = updatedAt;
    }

    /**
     * 以当前版本推进整批替换命名空间。
     *
     * @param expectedVersion 调用方持有的已锁定版本
     * @param now 更新时间
     * @throws IllegalStateException 版本已变化时抛出
     */
    public void increment(long expectedVersion, Instant now) {
        if (version != expectedVersion) {
            throw new IllegalStateException("安全指令命名空间版本已变化");
        }
        version++;
        updatedAt = now;
    }
}
