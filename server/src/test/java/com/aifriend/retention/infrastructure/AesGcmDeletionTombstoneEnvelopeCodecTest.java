package com.aifriend.retention.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.aifriend.retention.application.DeletionTombstoneExportRecord;
import com.aifriend.retention.application.DisasterRecoveryProperties;
import com.aifriend.retention.application.EncryptedDeletionTombstoneEnvelope;
import com.aifriend.shared.security.DigestService;

class AesGcmDeletionTombstoneEnvelopeCodecTest {
    private static final Instant ACCEPTED_AT = Instant.parse("2026-08-20T10:00:00Z");

    @Test
    void shouldDeclareProductionConstructorForSpringInjection() throws NoSuchMethodException {
        assertTrue(AesGcmDeletionTombstoneEnvelopeCodec.class
                .getConstructor(DisasterRecoveryProperties.class, DigestService.class)
                .isAnnotationPresent(Autowired.class));
    }

    @Test
    void shouldRoundTripVersionedEncryptedEnvelopeWithoutPlainSubjectHash() {
        DigestService digestService = new DigestService();
        AesGcmDeletionTombstoneEnvelopeCodec codec = codec(digestService);
        DeletionTombstoneExportRecord expected = record();

        EncryptedDeletionTombstoneEnvelope envelope = codec.seal(expected);
        DeletionTombstoneExportRecord actual = codec.open(envelope);

        assertEquals(expected.tombstoneId(), actual.tombstoneId());
        assertArrayEquals(expected.subjectHash(), actual.subjectHash());
        assertEquals(expected.oldAccountGeneration(), actual.oldAccountGeneration());
        assertEquals(expected.acceptedAt(), actual.acceptedAt());
        assertEquals(expected.completedAt(), actual.completedAt());
        assertEquals(expected.reRegistrationNotBefore(), actual.reRegistrationNotBefore());
        assertEquals(expected.policyVersion(), actual.policyVersion());
        assertEquals(expected.replayUntil(), actual.replayUntil());
        assertFalse(contains(envelope.encryptedEnvelope(), expected.subjectHash()));
    }

    @Test
    void shouldRejectTamperedCipherEvenWhenOuterHashIsRecomputed() {
        DigestService digestService = new DigestService();
        AesGcmDeletionTombstoneEnvelopeCodec codec = codec(digestService);
        EncryptedDeletionTombstoneEnvelope original = codec.seal(record());
        byte[] tampered = original.encryptedEnvelope();
        tampered[tampered.length - 1] ^= 1;
        EncryptedDeletionTombstoneEnvelope forged = new EncryptedDeletionTombstoneEnvelope(
                original.keyId(), tampered, digestService.sha256(tampered));

        assertThrows(IllegalStateException.class, () -> codec.open(forged));
    }

    @Test
    void shouldRejectExportWhenDedicatedKeyIsDisabled() {
        DisasterRecoveryProperties properties = new DisasterRecoveryProperties(
                false, "", false, "", "");
        AesGcmDeletionTombstoneEnvelopeCodec codec =
                new AesGcmDeletionTombstoneEnvelopeCodec(properties, new DigestService());

        assertThrows(IllegalStateException.class, () -> codec.seal(record()));
    }

    @Test
    void shouldRejectInvalidEnabledKeyAndMaskValidKeyInConfigurationText() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DisasterRecoveryProperties(false, "", true, "dr-v1", "invalid"));
        DisasterRecoveryProperties properties = properties();

        assertFalse(properties.toString().contains(properties.exportEncryptionKeyBase64()));
    }

    @Test
    void shouldOpenExistingEnvelopeInRestoreModeWithoutEnablingNewExports() {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        String encodedKey = Base64.getEncoder().encodeToString(key);
        DigestService digestService = new DigestService();
        EncryptedDeletionTombstoneEnvelope envelope = codec(digestService).seal(record());
        DisasterRecoveryProperties restoreProperties = new DisasterRecoveryProperties(
                true,
                "snapshot-20260820",
                false,
                "dr-key-v1",
                encodedKey);
        AesGcmDeletionTombstoneEnvelopeCodec restoreCodec =
                new AesGcmDeletionTombstoneEnvelopeCodec(restoreProperties, digestService);

        assertEquals(record().tombstoneId(), restoreCodec.open(envelope).tombstoneId());
        assertThrows(IllegalStateException.class, () -> restoreCodec.seal(record()));
    }

    private AesGcmDeletionTombstoneEnvelopeCodec codec(DigestService digestService) {
        return new AesGcmDeletionTombstoneEnvelopeCodec(properties(), digestService);
    }

    private DisasterRecoveryProperties properties() {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        return new DisasterRecoveryProperties(
                false,
                "",
                true,
                "dr-key-v1",
                Base64.getEncoder().encodeToString(key));
    }

    private DeletionTombstoneExportRecord record() {
        byte[] subjectHash = new byte[32];
        java.util.Arrays.fill(subjectHash, (byte) 9);
        return new DeletionTombstoneExportRecord(
                UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"),
                subjectHash,
                3L,
                ACCEPTED_AT,
                ACCEPTED_AT.plusSeconds(60),
                ACCEPTED_AT.plusSeconds(72 * 60 * 60),
                "deletion-tombstone-v1",
                ACCEPTED_AT.plusSeconds(37 * 24 * 60 * 60));
    }

    private boolean contains(byte[] source, byte[] target) {
        for (int offset = 0; offset <= source.length - target.length; offset++) {
            boolean matches = true;
            for (int index = 0; index < target.length; index++) {
                if (source[offset + index] != target[index]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }
}
