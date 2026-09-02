package com.aifriend.invitation.infrastructure;

import java.net.URI;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.aifriend.invitation.application.WechatInvitationAuthorizationPort;
import com.aifriend.shared.error.UpstreamFailureException;

/**
 * 生产环境真实微信邀请 OAuth 接入前的保守失败实现。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
@Profile("prod")
public class UnavailableWechatInvitationAuthorizationAdapter implements WechatInvitationAuthorizationPort {

    /**
     * 创建生产环境保守失败适配器。
     */
    public UnavailableWechatInvitationAuthorizationAdapter() {
    }

    /**
     * 始终拒绝构造生产 OAuth 地址，避免未授权调用或伪造成功。
     *
     * @param oauthState 一次性 OAuth state
     * @return 不会返回
     * @throws UpstreamFailureException 始终抛出，表示真实微信 OAuth 尚未接入
     */
    @Override
    public URI authorizationUrl(String oauthState) {
        throw new UpstreamFailureException();
    }
}
