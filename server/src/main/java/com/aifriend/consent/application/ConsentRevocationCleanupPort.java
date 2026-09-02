package com.aifriend.consent.application;

import java.time.Instant;
import java.util.UUID;

import com.aifriend.consent.domain.ConsentType;

/**
 * 授权撤回后的同事务逻辑清理端口。
 *
 * <p>实现只能写入当前数据库的最小删除事实，不得在授权事务中访问对象存储。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
public interface ConsentRevocationCleanupPort {

    /**
     * 立即停止受撤回授权控制的数据使用并进入异步物理删除。
     *
     * @param userId 当前用户 UUID
     * @param type 被撤回的授权类型
     * @param revokedAt 撤回决定时间
     */
    void cleanup(UUID userId, ConsentType type, Instant revokedAt);
}
