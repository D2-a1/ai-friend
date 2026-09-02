package com.aifriend.identity.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.aifriend.identity.domain.UserStatus;

import lombok.Getter;

/**
 * 用户账号 JPA 实体。
 *
 * @author Codex
 * @since 1.0.0
 */
@Entity
@Table(name = "app_user")
@Getter
public class AppUserEntity {

    /**
     * 供 JPA 反射创建实体。
     */
    protected AppUserEntity() {
    }

    /**
     * 内部 UUID，数据库保存为 BINARY(16)。
     */
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID id;

    /**
     * 微信应用作用域主体 AES-GCM 密文。
     */
    @Column(name = "wechat_open_id_cipher", length = 512)
    private byte[] wechatOpenIdCipher;

    /**
     * 微信主体 HMAC 查询键。
     */
    @Column(name = "wechat_open_id_hash", columnDefinition = "BINARY(32)", unique = true)
    private byte[] wechatOpenIdHash;

    /**
     * 账号状态。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private UserStatus status;

    /**
     * 同一微信身份账号代次。
     */
    @Column(name = "account_generation", nullable = false)
    private long accountGeneration;

    /**
     * 称呼命名空间乐观版本。
     */
    @Column(name = "alias_namespace_version", nullable = false)
    private long aliasNamespaceVersion;

    /**
     * 实体乐观锁版本。
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * 创建时间，UTC。
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * 更新时间，UTC。
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 在已持有用户写锁时推进称呼命名空间版本。
     *
     * @param expectedVersion 锁定后读取的当前版本
     * @param now 更新时间
     * @throws IllegalStateException 当前版本已变化时抛出
     */
    public void incrementAliasNamespace(long expectedVersion, Instant now) {
        if (aliasNamespaceVersion != expectedVersion) {
            throw new IllegalStateException("称呼命名空间版本已变化");
        }
        aliasNamespaceVersion++;
        updatedAt = now;
    }
}
