package com.aifriend.retention.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class TencentCosDisasterRecoveryPreflightOptionsTest {

    @Test
    void shouldAcceptSeparateIdentitiesAndRedactAllSensitiveValues() {
        TencentCosDisasterRecoveryPreflightOptions options = options();

        assertThat(options.toString())
                .contains("ap-shanghai")
                .doesNotContain(
                        "ai-friend-tombstones-1250000000",
                        "publisher-secret-id",
                        "publisher-secret-key",
                        "export-secret-id",
                        "export-secret-key",
                        "restore-secret-id",
                        "restore-secret-key");
    }

    @Test
    void shouldRejectReusedIdentityPlaceholderAndInvalidTimeout() {
        assertThatThrownBy(() -> options("publisher-secret-id", Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("相互独立");
        assertThatThrownBy(() -> new TencentCosDisasterRecoveryPreflightOptions(
                "ap-shanghai",
                "ai-friend-tombstones-1250000000",
                "publisher-secret-id",
                "publisher-secret-key",
                "",
                "export-secret-id",
                "REPLACE_EXPORT_SECRET_KEY",
                "",
                "restore-secret-id",
                "restore-secret-key",
                "",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未配置");
        assertThatThrownBy(() -> options("restore-secret-id", Duration.ofSeconds(31)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("30 秒");
    }

    private static TencentCosDisasterRecoveryPreflightOptions options() {
        return options("restore-secret-id", Duration.ofSeconds(2));
    }

    private static TencentCosDisasterRecoveryPreflightOptions options(
            String restoreSecretId,
            Duration connectTimeout) {
        return new TencentCosDisasterRecoveryPreflightOptions(
                "ap-shanghai",
                "ai-friend-tombstones-1250000000",
                "publisher-secret-id",
                "publisher-secret-key",
                "",
                "export-secret-id",
                "export-secret-key",
                "",
                restoreSecretId,
                "restore-secret-key",
                "",
                connectTimeout,
                Duration.ofSeconds(5));
    }
}
