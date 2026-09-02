package com.aifriend.shared.security;

import java.util.UUID;

/**
 * 已认证 JWT 对应账号是否仍可使用的最小安全端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface ActiveAccountStatusPort {

    /**
     * 判断账号是否仍为 ACTIVE。
     *
     * @param userId JWT 派生的内部用户 UUID
     * @return 账号存在且状态为 ACTIVE 时返回 true
     */
    boolean isActive(UUID userId);
}
