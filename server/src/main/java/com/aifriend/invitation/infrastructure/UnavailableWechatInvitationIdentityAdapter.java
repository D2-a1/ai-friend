package com.aifriend.invitation.infrastructure;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.aifriend.invitation.application.WechatInvitationIdentity;
import com.aifriend.invitation.application.WechatInvitationIdentityPort;
import com.aifriend.shared.error.UpstreamFailureException;

/**
 * 生产环境真实微信邀请 OAuth 接入前的保守失败身份适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
@Profile("prod")
public class UnavailableWechatInvitationIdentityAdapter implements WechatInvitationIdentityPort {

    /**
     * 创建生产环境保守失败适配器。
     */
    public UnavailableWechatInvitationIdentityAdapter() {
    }

    /**
     * 拒绝消费任何 code，避免在未接真实微信时伪造身份。
     *
     * @param code OAuth code，不读取、不记录
     * @return 不会返回
     * @throws UpstreamFailureException 始终抛出
     */
    @Override
    public WechatInvitationIdentity exchangeCode(String code) {
        throw new UpstreamFailureException();
    }
}
