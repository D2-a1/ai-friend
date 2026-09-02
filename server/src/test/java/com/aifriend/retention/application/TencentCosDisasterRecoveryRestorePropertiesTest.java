package com.aifriend.retention.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class TencentCosDisasterRecoveryRestorePropertiesTest {

    @Test
    void shouldAllowDisabledConfigurationWithoutCredentials() {
        assertDoesNotThrow(() -> properties(false, "", "", "", "", ""));
    }

    @Test
    void shouldAcceptSeparateReadOnlyIdentityAndEd25519PublicKey() throws Exception {
        TencentCosDisasterRecoveryRestoreProperties properties = properties(
                true,
                "ap-shanghai",
                "ai-friend-tombstones-1250000000",
                "restore-secret-id",
                "restore-secret-key",
                publicKeyBase64());

        assertDoesNotThrow(properties::requireManifestPublicKey);
    }

    @Test
    void shouldRejectUnsupportedRegionPlaceholderAndMalformedPublicKey() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> properties(
                true,
                "ap-guangzhou",
                validBucket(),
                "restore-id",
                "restore-key",
                publicKeyBase64()));
        assertThrows(IllegalArgumentException.class, () -> properties(
                true,
                "ap-shanghai",
                validBucket(),
                "REPLACE_ID",
                "restore-key",
                publicKeyBase64()));
        assertThrows(IllegalArgumentException.class, () -> properties(
                true,
                "ap-shanghai",
                validBucket(),
                "restore-id",
                "restore-key",
                Base64.getEncoder().encodeToString(new byte[44])));
    }

    @Test
    void shouldRedactCredentialsBucketAndPublicKey() throws Exception {
        String publicKey = publicKeyBase64();
        TencentCosDisasterRecoveryRestoreProperties properties = properties(
                true,
                "ap-nanjing",
                validBucket(),
                "visible-restore-id",
                "visible-restore-key",
                publicKey);

        String description = properties.toString();

        assertFalse(description.contains(validBucket()));
        assertFalse(description.contains("visible-restore-id"));
        assertFalse(description.contains("visible-restore-key"));
        assertFalse(description.contains(publicKey));
    }

    private static TencentCosDisasterRecoveryRestoreProperties properties(
            boolean enabled,
            String region,
            String bucket,
            String secretId,
            String secretKey,
            String publicKeyBase64) {
        return new TencentCosDisasterRecoveryRestoreProperties(
                enabled,
                region,
                bucket,
                secretId,
                secretKey,
                "",
                publicKeyBase64,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
    }

    private static String publicKeyBase64() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    private static String validBucket() {
        return "ai-friend-tombstones-1250000000";
    }
}
