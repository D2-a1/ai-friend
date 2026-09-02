package com.aifriend.invitation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * 生产微信邀请网页 OAuth 配置边界测试。
 *
 * @author Codex
 * @since 1.0.0
 */
class WechatInvitationOAuthPropertiesTest {

    private static final String APP_ID = "wx-invitation-app-id";
    private static final String APP_SECRET = "invitation-app-secret-0123456789";
    private static final URI CALLBACK =
            URI.create("https://api.ai-friend.asia/api/v1/oauth/wechat/invitation-callback");

    @Test
    void shouldAllowDisabledPlaceholderWithoutExposingCredentials() {
        WechatInvitationOAuthProperties properties = properties(
                false, "", "", URI.create("https://invite.example.com/oauth/wechat/invitation-callback"));

        assertThat(properties.toString())
                .contains("enabled=false", "appId=***", "appSecret=***")
                .doesNotContain(APP_ID, APP_SECRET);
    }

    @Test
    void shouldAcceptValidProductionConfigurationAndRedactCredentials() {
        WechatInvitationOAuthProperties properties =
                properties(true, APP_ID, APP_SECRET, CALLBACK);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.callbackUrl()).isEqualTo(CALLBACK);
        assertThat(properties.toString()).doesNotContain(APP_ID, APP_SECRET);
    }

    @Test
    void shouldRejectEnabledConfigurationWithoutCredentialsOrWithPlaceholderCallback() {
        assertThatThrownBy(() -> properties(true, "", "", CALLBACK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(
                        true,
                        APP_ID,
                        APP_SECRET,
                        URI.create("https://invite.example.com/oauth/wechat/invitation-callback")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonOfficialEndpointUnsafeCallbackOrTimeout() {
        assertThatThrownBy(() -> new WechatInvitationOAuthProperties(
                        true,
                        APP_ID,
                        APP_SECRET,
                        URI.create("https://example.com/connect/oauth2/authorize"),
                        WechatInvitationOAuthProperties.OFFICIAL_TOKEN_ENDPOINT,
                        CALLBACK,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(3)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(
                        true,
                        APP_ID,
                        APP_SECRET,
                        URI.create("http://api.ai-friend.asia/api/v1/oauth/wechat/invitation-callback")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WechatInvitationOAuthProperties(
                        true,
                        APP_ID,
                        APP_SECRET,
                        WechatInvitationOAuthProperties.OFFICIAL_AUTHORIZATION_ENDPOINT,
                        WechatInvitationOAuthProperties.OFFICIAL_TOKEN_ENDPOINT,
                        CALLBACK,
                        Duration.ofSeconds(6),
                        Duration.ofSeconds(3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private WechatInvitationOAuthProperties properties(
            boolean enabled,
            String appId,
            String appSecret,
            URI callback) {
        return new WechatInvitationOAuthProperties(
                enabled,
                appId,
                appSecret,
                WechatInvitationOAuthProperties.OFFICIAL_AUTHORIZATION_ENDPOINT,
                WechatInvitationOAuthProperties.OFFICIAL_TOKEN_ENDPOINT,
                callback,
                Duration.ofSeconds(2),
                Duration.ofSeconds(3));
    }
}
