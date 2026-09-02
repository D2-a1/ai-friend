package com.aifriend.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * 生产微信身份配置安全边界测试。
 *
 * @author Codex
 * @since 1.0.0
 */
class WechatIdentityPropertiesTest {

    private static final String APP_ID = "wx1234567890abcdef";
    private static final String APP_SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void shouldAllowEmptyCredentialsOnlyWhenDisabled() {
        WechatIdentityProperties properties = properties(false, "", "");

        assertThat(properties.enabled()).isFalse();
    }

    @Test
    void shouldRejectMissingOrPlaceholderCredentialsWhenEnabled() {
        assertThatIllegalArgumentException().isThrownBy(() -> properties(true, "", APP_SECRET));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties(true, APP_ID, "REPLACE_WECHAT_APP_SECRET"));
    }

    @Test
    void shouldRejectNonOfficialEndpointAndUnsafeTimeout() {
        assertThatIllegalArgumentException().isThrownBy(() -> new WechatIdentityProperties(
                true,
                APP_ID,
                APP_SECRET,
                URI.create("https://example.com/sns/oauth2/access_token"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(3)));
        assertThatIllegalArgumentException().isThrownBy(() -> new WechatIdentityProperties(
                true,
                APP_ID,
                APP_SECRET,
                WechatIdentityProperties.OFFICIAL_TOKEN_ENDPOINT,
                Duration.ZERO,
                Duration.ofSeconds(3)));
    }

    @Test
    void shouldRedactCredentialsFromConfigurationText() {
        String configurationText = properties(true, APP_ID, APP_SECRET).toString();

        assertThat(configurationText)
                .doesNotContain(APP_ID)
                .doesNotContain(APP_SECRET)
                .contains("appId=***", "appSecret=***");
    }

    private WechatIdentityProperties properties(boolean enabled, String appId, String appSecret) {
        return new WechatIdentityProperties(
                enabled,
                appId,
                appSecret,
                WechatIdentityProperties.OFFICIAL_TOKEN_ENDPOINT,
                Duration.ofSeconds(2),
                Duration.ofSeconds(3));
    }
}
