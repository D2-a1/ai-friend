package com.aifriend.retention.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.retention.application.DeletionTombstoneEnvelopeCodecPort;
import com.aifriend.retention.application.DeletionTombstoneExportRecord;
import com.aifriend.retention.application.DeletionTombstoneRestoreBatch;
import com.aifriend.retention.application.DeletionTombstoneRestoreDigest;
import com.aifriend.retention.application.DeletionTombstoneRestoreEntry;
import com.aifriend.retention.application.DeletionTombstoneRestoreManifest;
import com.aifriend.retention.application.DeletionTombstoneRestoreSourcePort;
import com.aifriend.retention.application.EncryptedDeletionTombstoneEnvelope;
import com.aifriend.shared.security.DigestService;

class DisasterRecoverySnapshotRestoreVerifierCliTest {

    private static final String SNAPSHOT_ID = "snapshot-20260828T025000Z";
    private static final UUID TOMBSTONE_ID =
            UUID.fromString("018f4a44-9eb0-7ad0-8000-000000000001");

    @Test
    void shouldBuildMaskedOptionsFromProcessEnvironment() {
        Map<String, String> environment = environment();
        DisasterRecoverySnapshotRestoreVerifierCli.VerificationOptions options =
                DisasterRecoverySnapshotRestoreVerifierCli.options(environment);

        assertThat(options.snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(options.restoreProperties().enabled()).isTrue();
        assertThat(options.coreProperties().restoreMode()).isTrue();
        assertThat(options.toString())
                .doesNotContain(environment.get(
                        "AI_FRIEND_DR_VERIFY_RESTORE_SECRET_ID"))
                .doesNotContain(environment.get(
                        "AI_FRIEND_DR_VERIFY_RESTORE_SECRET_KEY"))
                .doesNotContain(environment.get(
                        "AI_FRIEND_DR_VERIFY_EXPORT_KEY_BASE64"))
                .doesNotContain(environment.get(
                        "AI_FRIEND_DR_VERIFY_MANIFEST_PUBLIC_KEY_X509_BASE64"));
    }

    @Test
    void shouldRejectReusedRestoreAndExportIdentity() {
        Map<String, String> environment = new java.util.HashMap<>(environment());
        environment.put("AI_FRIEND_DR_VERIFY_EXPORT_SECRET_ID", "restore-secret-id");

        assertThatThrownBy(() ->
                DisasterRecoverySnapshotRestoreVerifierCli.options(environment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不得复用");
    }

    @Test
    void shouldVerifySnapshotWithoutRepository() {
        DigestService digestService = new DigestService();
        DeletionTombstoneRestoreEntry entry = entry(digestService);
        DeletionTombstoneRestoreDigest aggregate =
                new DeletionTombstoneRestoreDigest(SNAPSHOT_ID, 1L);
        aggregate.update(entry);
        DeletionTombstoneRestoreManifest manifest =
                new DeletionTombstoneRestoreManifest(
                        SNAPSHOT_ID,
                        1L,
                        aggregate.finish(),
                        digestService.sha256("source-proof"));
        DeletionTombstoneRestoreSourcePort source =
                mock(DeletionTombstoneRestoreSourcePort.class);
        DeletionTombstoneEnvelopeCodecPort codec =
                mock(DeletionTombstoneEnvelopeCodecPort.class);
        DeletionTombstoneExportRecord record = record(TOMBSTONE_ID);
        when(source.openSnapshot(SNAPSHOT_ID)).thenReturn(manifest);
        when(source.readBatch(SNAPSHOT_ID, null, 100))
                .thenReturn(new DeletionTombstoneRestoreBatch(
                        List.of(entry), null, true));
        when(codec.open(entry.envelope())).thenReturn(record);

        DisasterRecoverySnapshotRestoreVerifierCli.SnapshotVerificationReceipt receipt =
                DisasterRecoverySnapshotRestoreVerifierCli.verifySnapshot(
                        source, codec, digestService, SNAPSHOT_ID);

        assertThat(receipt.snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(receipt.itemCount()).isEqualTo(1L);
        assertThat(receipt.aggregateHash()).isEqualTo(manifest.aggregateHash());
        verify(source).openSnapshot(SNAPSHOT_ID);
        verify(source).readBatch(SNAPSHOT_ID, null, 100);
        verify(codec).open(entry.envelope());
    }

    @Test
    void shouldFailClosedForEnvelopeIdentityMismatch() {
        DigestService digestService = new DigestService();
        DeletionTombstoneRestoreEntry entry = entry(digestService);
        DeletionTombstoneRestoreDigest aggregate =
                new DeletionTombstoneRestoreDigest(SNAPSHOT_ID, 1L);
        aggregate.update(entry);
        DeletionTombstoneRestoreSourcePort source =
                mock(DeletionTombstoneRestoreSourcePort.class);
        DeletionTombstoneEnvelopeCodecPort codec =
                mock(DeletionTombstoneEnvelopeCodecPort.class);
        when(source.openSnapshot(SNAPSHOT_ID)).thenReturn(
                new DeletionTombstoneRestoreManifest(
                        SNAPSHOT_ID,
                        1L,
                        aggregate.finish(),
                        digestService.sha256("source-proof")));
        when(source.readBatch(SNAPSHOT_ID, null, 100))
                .thenReturn(new DeletionTombstoneRestoreBatch(
                        List.of(entry), null, true));
        when(codec.open(entry.envelope())).thenReturn(record(
                UUID.fromString("018f4a44-9eb0-7ad0-8000-000000000002")));

        assertThatThrownBy(() ->
                DisasterRecoverySnapshotRestoreVerifierCli.verifySnapshot(
                        source, codec, digestService, SNAPSHOT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UUID 不一致");
    }

    private static Map<String, String> environment() {
        return Map.ofEntries(
                Map.entry("AI_FRIEND_DR_VERIFY_REGION", "ap-shanghai"),
                Map.entry(
                        "AI_FRIEND_DR_VERIFY_BUCKET",
                        "ai-friend-tombstones-1250000000"),
                Map.entry("AI_FRIEND_DR_VERIFY_SNAPSHOT_ID", SNAPSHOT_ID),
                Map.entry(
                        "AI_FRIEND_DR_VERIFY_RESTORE_SECRET_ID",
                        "restore-secret-id"),
                Map.entry(
                        "AI_FRIEND_DR_VERIFY_RESTORE_SECRET_KEY",
                        "restore-secret-key"),
                Map.entry(
                        "AI_FRIEND_DR_VERIFY_EXPORT_SECRET_ID",
                        "export-secret-id"),
                Map.entry("AI_FRIEND_DR_VERIFY_EXPORT_KEY_ID", "dr-key-v1"),
                Map.entry(
                        "AI_FRIEND_DR_VERIFY_EXPORT_KEY_BASE64",
                        Base64.getEncoder().encodeToString(new byte[32])),
                Map.entry(
                        "AI_FRIEND_DR_VERIFY_MANIFEST_PUBLIC_KEY_X509_BASE64",
                        Base64.getEncoder().encodeToString(
                                KeyPairGeneratorHolder.PUBLIC_KEY)));
    }

    private static DeletionTombstoneRestoreEntry entry(DigestService digestService) {
        byte[] content = new byte[96];
        Arrays.fill(content, (byte) 7);
        return new DeletionTombstoneRestoreEntry(
                TOMBSTONE_ID,
                new EncryptedDeletionTombstoneEnvelope(
                        "dr-key-v1",
                        content,
                        digestService.sha256(content)));
    }

    private static DeletionTombstoneExportRecord record(UUID tombstoneId) {
        Instant acceptedAt = Instant.parse("2026-08-27T00:00:00Z");
        Instant completedAt = Instant.parse("2026-08-27T00:01:00Z");
        return new DeletionTombstoneExportRecord(
                tombstoneId,
                new byte[32],
                1L,
                acceptedAt,
                completedAt,
                acceptedAt.plusSeconds(72 * 60 * 60),
                "v1",
                completedAt.plusSeconds(37L * 24 * 60 * 60));
    }

    private static final class KeyPairGeneratorHolder {

        private static final byte[] PUBLIC_KEY = generatePublicKey();

        private KeyPairGeneratorHolder() {
        }

        private static byte[] generatePublicKey() {
            try {
                return java.security.KeyPairGenerator.getInstance("Ed25519")
                        .generateKeyPair()
                        .getPublic()
                        .getEncoded();
            } catch (java.security.GeneralSecurityException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }
    }
}
