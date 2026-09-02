package com.aifriend.consent.domain;

/**
 * 授权决定。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum ConsentDecision {
    /** 用户明确同意该项处理。 */
    GRANTED,
    /** 用户明确撤回该项同意。 */
    REVOKED
}
