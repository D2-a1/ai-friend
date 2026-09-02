package com.aifriend.voice.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * 阿里云 OSS 私有音频存储配置边界测试。
 *
 * @author Codex
 * @since 1.0.0
 */
class AliyunOssStoragePropertiesTest {

    @Test
    void shouldAllowMissingProductionValuesOnlyWhileDisabled() {
        AliyunOssStorageProperties properties =
                new AliyunOssStorageProperties(
                        false, null, null, null, null, null,
                        null, null, null, null);

        assertEquals("", properties.region());
        assertEquals("", properties.bucket());
    }

    @Test
    void shouldAcceptOfficialChinaEndpointsAndBoundedTimeouts() {
        assertDoesNotThrow(() -> enabledProperties(
                URI.create("https://oss-cn-shanghai-internal.aliyuncs.com"),
                URI.create("https://oss-cn-shanghai.aliyuncs.com")));
    }

    @Test
    void shouldRejectNonHttpsOrMismatchedUploadEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> enabledProperties(
                URI.create("https://oss-cn-shanghai.aliyuncs.com"),
                URI.create("http://oss-cn-shanghai.aliyuncs.com")));
        assertThrows(IllegalArgumentException.class, () -> enabledProperties(
                URI.create("https://oss-cn-shanghai.aliyuncs.com"),
                URI.create("https://oss-cn-hangzhou.aliyuncs.com")));
    }

    @Test
    void shouldRejectPlaceholderCredentialsAndUnsafeTimeouts() {
        assertThrows(IllegalArgumentException.class,
                () -> new AliyunOssStorageProperties(
                        true,
                        "cn-shanghai",
                        URI.create("https://oss-cn-shanghai.aliyuncs.com"),
                        URI.create("https://oss-cn-shanghai.aliyuncs.com"),
                        "ai-friend-audio",
                        "REPLACE_ACCESS_KEY",
                        "secret",
                        "",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> new AliyunOssStorageProperties(
                        true,
                        "cn-shanghai",
                        URI.create("https://oss-cn-shanghai.aliyuncs.com"),
                        URI.create("https://oss-cn-shanghai.aliyuncs.com"),
                        "ai-friend-audio",
                        "access-key",
                        "secret",
                        "",
                        Duration.ofMillis(50),
                        Duration.ofSeconds(5)));
    }

    private AliyunOssStorageProperties enabledProperties(
            URI serviceEndpoint,
            URI uploadEndpoint) {
        return new AliyunOssStorageProperties(
                true,
                "cn-shanghai",
                serviceEndpoint,
                uploadEndpoint,
                "ai-friend-audio",
                "access-key",
                "access-secret",
                "",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
    }
}
