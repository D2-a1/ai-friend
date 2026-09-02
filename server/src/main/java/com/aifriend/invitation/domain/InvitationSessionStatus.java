package com.aifriend.invitation.domain;

/**
 * 受限邀请会话内部状态。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum InvitationSessionStatus {
    /** 等待亲友完成微信 OAuth。 */
    AWAITING_WECHAT_OAUTH,
    /** 微信身份已验证，等待亲友明确决定。 */
    WECHAT_VERIFIED,
    /** 邀请撤销、拒绝或接受后，会话已终止。 */
    TERMINATED,
    /** 固定 30 分钟会话已经过期。 */
    EXPIRED
}
