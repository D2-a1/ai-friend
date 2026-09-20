package com.aifriend.consent.application;

import java.time.Instant;
import java.util.UUID;

import com.aifriend.consent.domain.ConsentType;

/**
 * 单个业务域对授权撤回的同事务清理处理器。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface ConsentRevocationCleanupHandler {

    /**
     * 清理该业务域受指定授权控制的数据；不相关类型直接返回。
     *
     * @param userId 当前用户 UUID
     * @param type 被撤回的授权类型
     * @param revokedAt 撤回时间
     */
    void cleanup(UUID userId, ConsentType type, Instant revokedAt);
}
