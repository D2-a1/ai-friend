package com.aifriend.retention.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class TencentCosDisasterRecoveryPropertiesTest {

    @Test
    void shouldAllowDisabledConfigurationWithoutSecrets() {
        assertDoesNotThrow(() -> properties(
                false, "", "", "", "", null));
    }

    @Test
    void shouldAcceptIndependentMainlandRegionAndFullBucketName() {
        assertDoesNotThrow(() -> properties(
                true,
                "ap-shanghai",
                "ai-friend-deletion-tombstones-1250000000",
                "secret-id-value",
                "secret-key-value",
                "temporary-token"));
    }

    @Test
    void shouldRejectGuangzhouHongKongAndUnknownRegions() {
        assertThrows(IllegalArgumentException.class, () -> properties(
                true, "ap-guangzhou", validBucket(), "id", "key", ""));
        assertThrows(IllegalArgumentException.class, () -> properties(
                true, "ap-hongkong", validBucket(), "id", "key", ""));
        assertThrows(IllegalArgumentException.class, () -> properties(
                true, "eu-frankfurt", validBucket(), "id", "key", ""));
    }

    @Test
    void shouldRejectInvalidBucketPlaceholdersAndTimeouts() {
        assertThrows(IllegalArgumentException.class, () -> properties(
                true, "ap-beijing", "missing-app-id", "id", "key", ""));
        assertThrows(IllegalArgumentException.class, () -> properties(
                true, "ap-beijing", validBucket(), "REPLACE_ID", "key", ""));
        assertThrows(IllegalArgumentException.class, () -> properties(
                true, "ap-beijing", validBucket(), "id", "key", "REPLACE_TOKEN"));
        assertThrows(IllegalArgumentException.class, () -> properties(
                true, "ap-beijing",
                "a".repeat(49) + "-12500000000000000000",
                "id", "key", ""));
        assertThrows(IllegalArgumentException.class, () -> new TencentCosDisasterRecoveryProperties(
                true,
                "ap-beijing",
                validBucket(),
                "id",
                "key",
                "",
                Duration.ofMillis(99),
                Duration.ofSeconds(5)));
    }

    @Test
    void shouldRedactBucketCredentialsAndSessionToken() {
        TencentCosDisasterRecoveryProperties properties = properties(
                true,
                "ap-nanjing",
                validBucket(),
                "visible-secret-id",
                "visible-secret-key",
                "visible-session-token");

        String description = properties.toString();

        assertFalse(description.contains(validBucket()));
        assertFalse(description.contains("visible-secret-id"));
        assertFalse(description.contains("visible-secret-key"));
        assertFalse(description.contains("visible-session-token"));
    }

    private static TencentCosDisasterRecoveryProperties properties(
            boolean enabled,
            String region,
            String bucket,
            String secretId,
            String secretKey,
            String sessionToken) {
        return new TencentCosDisasterRecoveryProperties(
                enabled,
                region,
                bucket,
                secretId,
                secretKey,
                sessionToken,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
    }

    private static String validBucket() {
        return "ai-friend-tombstones-1250000000";
    }
}
