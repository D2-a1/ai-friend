package com.aifriend.contact.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 用户级称呼命名空间锁和版本端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface AliasNamespaceRepositoryPort {

    /**
     * 悲观锁定 owner 账号并读取当前称呼命名空间版本。
     *
     * @param ownerUserId owner UUID
     * @return 当前命名空间版本
     */
    long lock(UUID ownerUserId);

    /**
     * 在已持有 owner 锁的事务内推进命名空间版本。
     *
     * @param ownerUserId owner UUID
     * @param expectedVersion 锁定时读取的版本
     * @param now 更新时间
     */
    void increment(UUID ownerUserId, long expectedVersion, Instant now);
}
