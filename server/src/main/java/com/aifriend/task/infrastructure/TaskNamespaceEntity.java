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
 * owner 任务命名空间锁实体。
 *
 * @author Codex
 * @since 1.0.0
 */
@Entity
@Table(name = "task_namespace")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskNamespaceEntity {

    /** owner UUID，同时是主键。 */
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "owner_user_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID ownerUserId;

    /** 命名空间版本。 */
    @Column(name = "version", nullable = false)
    private long version;

    /** 更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 创建 owner 任务命名空间。
     *
     * @param ownerUserId owner UUID
     * @param version 初始版本
     * @param updatedAt 创建时间
     */
    public TaskNamespaceEntity(UUID ownerUserId, long version, Instant updatedAt) {
        this.ownerUserId = ownerUserId;
        this.version = version;
        this.updatedAt = updatedAt;
    }
}
