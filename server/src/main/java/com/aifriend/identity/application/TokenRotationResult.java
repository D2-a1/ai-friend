package com.aifriend.identity.application;

/**
 * 刷新令牌轮换事务结果。
 *
 * @param status 轮换状态
 * @param tokenPair 成功时的新令牌对
 * @author Codex
 * @since 1.0.0
 */
public record TokenRotationResult(Status status, TokenPairResult tokenPair) {

    /**
     * 轮换结果状态。
     */
    public enum Status {
        /** 轮换成功并产生新令牌对。 */
        SUCCESS,
        /** 检测到旧刷新令牌重放，整个 family 已撤销。 */
        COMPROMISED,
        /** 令牌已过期、已撤销或账号不可用。 */
        EXPIRED
    }

    /**
     * 创建成功结果。
     *
     * @param tokenPair 新令牌对
     * @return 成功结果
     */
    public static TokenRotationResult success(TokenPairResult tokenPair) {
        return new TokenRotationResult(Status.SUCCESS, tokenPair);
    }

    /**
     * 创建令牌重用结果。
     *
     * @return compromised 结果
     */
    public static TokenRotationResult compromised() {
        return new TokenRotationResult(Status.COMPROMISED, null);
    }

    /**
     * 创建过期或账号失效结果。
     *
     * @return expired 结果
     */
    public static TokenRotationResult expired() {
        return new TokenRotationResult(Status.EXPIRED, null);
    }
}
