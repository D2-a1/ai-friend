package com.aifriend.invitation.infrastructure;

import java.net.URI;

import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import com.aifriend.invitation.application.WechatInvitationAuthorizationPort;
import com.aifriend.invitation.application.WechatInvitationOAuthProperties;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 生产微信邀请网页 OAuth 授权入口适配器。
 *
 * <p>只构造固定微信官方授权地址，不发起网络请求。授权范围固定为
 * snsapi_base，仅申请静默取得当前公众号 AppID 范围 openid 所需的最小权限。
 *
 * @author Codex
 * @since 1.0.0
 */
public class WechatInvitationAuthorizationAdapter implements WechatInvitationAuthorizationPort {

    /** 微信网页授权最小范围。 */
    private static final String MINIMUM_SCOPE = "snsapi_base";

    private final WechatInvitationOAuthProperties properties;

    /**
     * 创建生产微信邀请授权入口适配器。
     *
     * @param properties 邀请网页 OAuth 固定配置
     */
    public WechatInvitationAuthorizationAdapter(WechatInvitationOAuthProperties properties) {
        this.properties = properties;
    }

    /**
     * 构造带一次性 state 的微信官方网页授权地址。
     *
     * @param oauthState 一次性 OAuth state，不得记录或持久化明文
     * @return 固定微信官方授权地址
     * @throws BusinessException 当 state 格式不符合安全边界时抛出
     */
    @Override
    public URI authorizationUrl(String oauthState) {
        validateState(oauthState);
        return UriComponentsBuilder.fromUri(properties.authorizationEndpoint())
                .queryParam("appid", properties.appId())
                .queryParam("redirect_uri", properties.callbackUrl().toString())
                .queryParam("response_type", "code")
                .queryParam("scope", MINIMUM_SCOPE)
                .queryParam("state", oauthState)
                .fragment("wechat_redirect")
                .build()
                .encode()
                .toUri();
    }

    private void validateState(String oauthState) {
        if (!StringUtils.hasText(oauthState)
                || oauthState.length() < 32
                || oauthState.length() > 256
                || oauthState.chars().anyMatch(character -> character <= 0x20 || character > 0x7E)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "微信邀请授权状态无效");
        }
    }
}
