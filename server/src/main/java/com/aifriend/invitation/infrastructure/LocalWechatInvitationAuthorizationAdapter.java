package com.aifriend.invitation.infrastructure;

import java.net.URI;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import com.aifriend.invitation.application.InvitationProperties;
import com.aifriend.invitation.application.WechatInvitationAuthorizationPort;

/**
 * dev/test 环境本地邀请授权入口构造器。
 *
 * <p>只生成带一次性 state 的本地占位地址，不调用微信，也不伪造 OAuth 成功。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
@Profile({"dev", "test"})
public class LocalWechatInvitationAuthorizationAdapter implements WechatInvitationAuthorizationPort {

    private final InvitationProperties properties;

    /**
     * 创建本地授权入口构造器。
     *
     * @param properties 邀请页同源基础地址
     */
    public LocalWechatInvitationAuthorizationAdapter(InvitationProperties properties) {
        this.properties = properties;
    }

    /**
     * 生成不调用真实微信的 dev/test 占位授权地址。
     *
     * @param oauthState 一次性 OAuth state
     * @return 同源 dev 授权占位地址
     */
    @Override
    public URI authorizationUrl(String oauthState) {
        return UriComponentsBuilder.fromUri(properties.baseUrl())
                .replacePath("/dev/wechat-authorization")
                .replaceQuery(null)
                .fragment(null)
                .queryParam("state", oauthState)
                .build()
                .encode()
                .toUri();
    }
}
