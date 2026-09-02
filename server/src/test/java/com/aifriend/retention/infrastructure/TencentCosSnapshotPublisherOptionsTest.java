package com.aifriend.retention.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class TencentCosSnapshotPublisherOptionsTest {

    @Test
    void shouldAcceptSeparateIdentitiesAndRedactAllSensitiveValues() throws Exception {
        KeyPair keyPair = keyPair();
        TencentCosSnapshotPublisherOptions options = options(keyPair);

        assertThat(options.requirePrivateKey().getAlgorithm()).isEqualTo("EdDSA");
        assertThat(options.requirePublicKey().getAlgorithm()).isEqualTo("EdDSA");
        assertThat(options.toString())
                .contains("snapshot-20260828")
                .doesNotContain(
                        "publisher-secret-id",
                        "publisher-secret-key",
                        "export-secret-id",
                        "restore-secret-id",
                        Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()),
                        Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
    }

    @Test
    void shouldRejectAnyReusedCamIdentity() throws Exception {
        KeyPair keyPair = keyPair();

        assertThatThrownBy(() -> new TencentCosSnapshotPublisherOptions(
                "ap-shanghai",
                "ai-friend-tombstones-1250000000",
                "shared-secret-id",
                "publisher-secret-key",
                "",
                "shared-secret-id",
                "restore-secret-id",
                "snapshot-20260828",
                Instant.parse("2026-08-28T00:00:00Z"),
                100,
                Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("相互独立");
    }

    @Test
    void shouldRejectGuangzhouInvalidBucketAndOutOfRangePageSize() throws Exception {
        KeyPair keyPair = keyPair();

        assertThatThrownBy(() -> options(
                keyPair, "ap-guangzhou", "ai-friend-tombstones-1250000000", 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> options(
                keyPair, "ap-shanghai", "invalid-bucket", 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> options(
                keyPair, "ap-shanghai", "ai-friend-tombstones-1250000000", 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectMismatchedOrMalformedEd25519Material() throws Exception {
        KeyPair first = keyPair();
        KeyPair second = keyPair();

        assertThatThrownBy(() -> options(
                Base64.getEncoder().encodeToString(first.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(second.getPublic().getEncoded())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不匹配");
        assertThatThrownBy(() -> options(
                "not-base64",
                Base64.getEncoder().encodeToString(first.getPublic().getEncoded())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TencentCosSnapshotPublisherOptions options(KeyPair keyPair) {
        return options(
                keyPair,
                "ap-shanghai",
                "ai-friend-tombstones-1250000000",
                100);
    }

    private static TencentCosSnapshotPublisherOptions options(
            KeyPair keyPair,
            String region,
            String bucket,
            int pageSize) {
        return new TencentCosSnapshotPublisherOptions(
                region,
                bucket,
                "publisher-secret-id",
                "publisher-secret-key",
                "",
                "export-secret-id",
                "restore-secret-id",
                "snapshot-20260828",
                Instant.parse("2026-08-28T00:00:00Z"),
                pageSize,
                Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
    }

    private static TencentCosSnapshotPublisherOptions options(
            String privateKey,
            String publicKey) {
        return new TencentCosSnapshotPublisherOptions(
                "ap-shanghai",
                "ai-friend-tombstones-1250000000",
                "publisher-secret-id",
                "publisher-secret-key",
                "",
                "export-secret-id",
                "restore-secret-id",
                "snapshot-20260828",
                Instant.parse("2026-08-28T00:00:00Z"),
                100,
                privateKey,
                publicKey,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
    }

    private static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }
}
