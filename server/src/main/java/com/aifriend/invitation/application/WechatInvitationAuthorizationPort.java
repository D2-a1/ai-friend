package com.aifriend.invitation.application;

import java.net.URI;

/**
 * 微信邀请 OAuth 授权入口构造端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface WechatInvitationAuthorizationPort {

    /**
     * 使用当前邀请会话的一次性 state 构造授权入口，不消费 OAuth code。
     *
     * @param oauthState 一次性 OAuth state，不得记录或持久化明文
     * @return 微信授权入口
     */
    URI authorizationUrl(String oauthState);
}
