package com.aifriend.contact.domain;

/**
 * 联系人绑定内部状态，与 OpenAPI ContactStatus 保持一致。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum ContactStatus {
    /** 等待亲友知情同意。 */
    PENDING_CONSENT,
    /** 已同意，等待老人手机本机验证。 */
    PENDING_LOCAL_VERIFY,
    /** 已验证但尚无有效方言称呼。 */
    ACTIVE_NO_ALIAS,
    /** 已验证且至少有一个有效称呼。 */
    ACTIVE,
    /** 稳定定位失效，等待重新验证。 */
    REVERIFY_REQUIRED,
    /** 绑定已解除。 */
    REVOKED,
    /** 绑定因风险控制被阻断。 */
    BLOCKED
}
