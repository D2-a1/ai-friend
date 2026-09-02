package com.aifriend.invitation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;

import com.aifriend.invitation.application.WechatInvitationOAuthProperties;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 生产微信邀请授权入口适配器测试。
 *
 * @author Codex
 * @since 1.0.0
 */
class WechatInvitationAuthorizationAdapterTest {

    private static final String APP_ID = "wx-invitation-app-id";
    private static final String APP_SECRET = "invitation-app-secret-0123456789";
    private static final String OAUTH_STATE = "0123456789abcdef0123456789abcdef";
    private static final URI CALLBACK =
            URI.create("https://api.ai-friend.asia/api/v1/oauth/wechat/invitation-callback");

    @Test
    void shouldBuildMinimumScopeOfficialAuthorizationUrl() {
        WechatInvitationAuthorizationAdapter adapter =
                new WechatInvitationAuthorizationAdapter(properties());

        URI authorizationUrl = adapter.authorizationUrl(OAUTH_STATE);

        assertThat(authorizationUrl.getScheme()).isEqualTo("https");
        assertThat(authorizationUrl.getHost()).isEqualTo("open.weixin.qq.com");
        assertThat(authorizationUrl.getPath()).isEqualTo("/connect/oauth2/authorize");
        assertThat(authorizationUrl.getFragment()).isEqualTo("wechat_redirect");
        var query = UriComponentsBuilder.fromUri(authorizationUrl).build().getQueryParams();
        assertThat(query.getFirst("appid")).isEqualTo(APP_ID);
        assertThat(query.getFirst("redirect_uri")).isEqualTo(CALLBACK.toString());
        assertThat(query.getFirst("response_type")).isEqualTo("code");
        assertThat(query.getFirst("scope")).isEqualTo("snsapi_base");
        assertThat(query.getFirst("state")).isEqualTo(OAUTH_STATE);
        assertThat(authorizationUrl.toString()).doesNotContain(APP_SECRET);
    }

    @Test
    void shouldRejectUnsafeStateBeforeBuildingUrl() {
        WechatInvitationAuthorizationAdapter adapter =
                new WechatInvitationAuthorizationAdapter(properties());

        assertThatThrownBy(() -> adapter.authorizationUrl("unsafe\nstate"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    private WechatInvitationOAuthProperties properties() {
        return new WechatInvitationOAuthProperties(
                true,
                APP_ID,
                APP_SECRET,
                WechatInvitationOAuthProperties.OFFICIAL_AUTHORIZATION_ENDPOINT,
                WechatInvitationOAuthProperties.OFFICIAL_TOKEN_ENDPOINT,
                CALLBACK,
                Duration.ofSeconds(2),
                Duration.ofSeconds(3));
    }
}
