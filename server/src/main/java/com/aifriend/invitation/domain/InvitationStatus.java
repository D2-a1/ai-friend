package com.aifriend.invitation.domain;

/**
 * 亲友邀请内部状态。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum InvitationStatus {
    /** 等待 proof 兑换。 */
    PENDING,
    /** proof 已兑换为受限邀请会话。 */
    PROOF_REDEEMED,
    /** 邀请会话已完成微信身份验证。 */
    WECHAT_VERIFIED,
    /** 亲友已同意并创建等待本机验证的绑定。 */
    ACCEPTED,
    /** 亲友已明确拒绝。 */
    DECLINED,
    /** 邀请人已撤销。 */
    REVOKED,
    /** 邀请已过期。 */
    EXPIRED;

    /**
     * 判断邀请是否仍未完成，可由邀请人撤销并占用未完成邀请配额。
     *
     * @return 等待 proof、已兑换 proof 或已完成微信身份校验时返回 true
     */
    public boolean isUnfinished() {
        return this == PENDING || this == PROOF_REDEEMED || this == WECHAT_VERIFIED;
    }
}
