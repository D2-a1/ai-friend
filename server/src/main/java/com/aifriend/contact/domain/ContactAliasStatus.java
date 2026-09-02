package com.aifriend.contact.domain;

/**
 * 联系人方言称呼生命周期状态。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum ContactAliasStatus {
    /** 当前可用于联系人候选匹配。 */
    ACTIVE,
    /** 已明确删除并清除可解密模板。 */
    DELETED,
    /** 模型或阈值版本不兼容，必须重新录入。 */
    INCOMPATIBLE
}
