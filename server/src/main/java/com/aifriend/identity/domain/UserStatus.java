package com.aifriend.identity.domain;

/**
 * 用户账号状态。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum UserStatus {
    /** 账号正常可用。 */
    ACTIVE,
    /** 注销已受理，账号数据正在删除。 */
    DELETING,
    /** 在线账号数据已删除。 */
    DELETED
}
