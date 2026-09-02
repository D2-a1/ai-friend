package com.aifriend.identity.domain;

/**
 * 刷新令牌状态。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum RefreshTokenStatus {
    /** 可用于一次轮换的活动令牌。 */
    ACTIVE,
    /** 已成功换取新令牌，禁止再次使用。 */
    ROTATED,
    /** 因风险、退出或 family 撤销而失效。 */
    REVOKED
}
