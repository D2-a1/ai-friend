package com.aifriend.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 应用配置值对象测试。
 *
 * @author Codex
 * @since 1.0.0
 */
class AiFriendPropertiesTest {

    @Test
    void shouldKeepConfiguredRetentionLimits() {
        var properties = new AiFriendProperties(
                new AiFriendProperties.Api("1.0.0"),
                new AiFriendProperties.Retention(Duration.ofHours(24), Duration.ofHours(24)));

        assertThat(properties.api().version()).isEqualTo("1.0.0");
        assertThat(properties.retention().temporaryAudioMaxAge()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void shouldRejectRetentionLongerThanTwentyFourHours() {
        assertThrows(IllegalArgumentException.class, () ->
                new AiFriendProperties.Retention(
                        Duration.ofHours(24).plusMillis(1), Duration.ofHours(24)));
    }

    @Test
    void shouldRejectNonPositiveRetention() {
        assertThrows(IllegalArgumentException.class, () ->
                new AiFriendProperties.Retention(Duration.ZERO, Duration.ofHours(24)));
    }
}
