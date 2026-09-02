package com.aifriend.retention.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class PushPlusAlertPropertiesTest {

    @Test
    void shouldAllowDisabledConfigurationWithoutCredentials() {
        PushPlusAlertProperties properties = new PushPlusAlertProperties(
                false, "", "", Duration.ofSeconds(2), Duration.ofSeconds(5));

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.toString()).contains("userToken=***", "secretKey=***");
    }

    @Test
    void shouldAcceptPersonalCredentialsWithoutExposingThem() {
        String token = "1234567890abcdef1234567890abcdef";
        String secretKey = "abcdef1234567890abcdef1234567890";
        PushPlusAlertProperties properties = new PushPlusAlertProperties(
                true, token, secretKey, Duration.ofSeconds(2), Duration.ofSeconds(5));

        assertThat(properties.toString())
                .doesNotContain(token)
                .doesNotContain(secretKey)
                .contains("enabled=true");
    }

    @Test
    void shouldRejectPlaceholderOrShortSecretWhenEnabled() {
        assertThatThrownBy(() -> new PushPlusAlertProperties(
                true,
                "REPLACE_PUSHPLUS_TOKEN",
                "abcdef1234567890abcdef1234567890",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PushPlusAlertProperties(
                true,
                "1234567890abcdef1234567890abcdef",
                "too-short",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
