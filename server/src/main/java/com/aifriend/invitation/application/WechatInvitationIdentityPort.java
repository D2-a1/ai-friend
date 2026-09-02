package com.aifriend.invitation.application;

/**
 * 微信邀请 OAuth code 验证端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface WechatInvitationIdentityPort {

    /**
     * 一次性兑换并验证微信 OAuth code。
     *
     * @param code 当前回调中的一次性 code，不得记录或持久化
     * @return 经微信或隔离开发适配器验证的最小身份
     */
    WechatInvitationIdentity exchangeCode(String code);
}
