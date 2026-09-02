package com.aifriend.retention.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.logging.TestLogCapture;

class DeletionTombstoneExportWorkerTest {
    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

    private DeletionTombstoneExportRepositoryPort repositoryPort;
    private DeletionTombstoneEnvelopeCodecPort codecPort;
    private DeletionTombstoneExportPort exportPort;
    private DigestService digestService;

    @BeforeEach
    void setUp() {
        repositoryPort = mock(DeletionTombstoneExportRepositoryPort.class);
        codecPort = mock(DeletionTombstoneEnvelopeCodecPort.class);
        exportPort = mock(DeletionTombstoneExportPort.class);
        digestService = new DigestService();
    }

    @Test
    void shouldNotReadTombstonesWhenExportIsDisabled() {
        DeletionTombstoneExportWorker worker = worker(disabledProperties());

        assertEquals(0, worker.processReady());

        verifyNoInteractions(repositoryPort, codecPort, exportPort);
    }

    @Test
    void shouldPrepareExportVerifyReadBackAndAtomicallyConfirmReceipt() {
        DeletionTombstoneExportCandidate candidate = candidate(null, 0);
        EncryptedDeletionTombstoneEnvelope envelope = envelope();
        byte[] receiptProof = new byte[16];
        when(repositoryPort.listReady(NOW, 10)).thenReturn(List.of(candidate));
        when(codecPort.seal(candidate.record())).thenReturn(envelope);
        when(repositoryPort.prepare(candidate.record().tombstoneId(), envelope, NOW))
                .thenReturn(true);
        when(exportPort.export(candidate.record().tombstoneId(), envelope))
                .thenReturn(new DeletionTombstoneExportReceipt(
                        envelope.envelopeHash(), receiptProof));
        when(repositoryPort.markExported(
                eq(candidate.record().tombstoneId()),
                any(byte[].class),
                eq(NOW),
                any(byte[].class))).thenReturn(true);
        DeletionTombstoneExportWorker worker = worker(enabledProperties());

        assertEquals(1, worker.processReady());

        verify(repositoryPort).markExported(
                candidate.record().tombstoneId(),
                envelope.envelopeHash(),
                NOW,
                digestService.sha256(receiptProof));
        verify(repositoryPort, never()).markRetry(any(), any(), any(), any());
    }

    @Test
    void shouldReuseFixedEnvelopeAndBackOffWhenReadBackHashDiffers() {
        EncryptedDeletionTombstoneEnvelope envelope = envelope();
        DeletionTombstoneExportCandidate candidate = candidate(envelope, 3);
        byte[] wrongHash = new byte[32];
        wrongHash[0] = 1;
        when(repositoryPort.listReady(NOW, 10)).thenReturn(List.of(candidate));
        when(exportPort.export(candidate.record().tombstoneId(), envelope))
                .thenReturn(new DeletionTombstoneExportReceipt(wrongHash, new byte[16]));
        DeletionTombstoneExportWorker worker = worker(enabledProperties());

        int exported;
        List<String> messages;
        try (TestLogCapture capture = TestLogCapture.forClass(
                DeletionTombstoneExportWorker.class)) {
            exported = worker.processReady();
            messages = capture.messages();
        }

        assertEquals(0, exported);

        verifyNoInteractions(codecPort);
        verify(repositoryPort).markRetry(
                candidate.record().tombstoneId(),
                envelope.envelopeHash(),
                NOW,
                NOW.plusSeconds(4 * 60));
        verify(repositoryPort, never()).markExported(any(), any(), any(), any());
        assertEquals(1, messages.size());
        String message = messages.get(0);
        assertTrue(message.contains("stage=DELETION_TOMBSTONE_EXPORT"));
        assertTrue(message.contains("errorType=IllegalStateException"));
        assertTrue(message.contains("retryCount=3"));
        assertTrue(message.contains("nextAttemptAt=2026-08-20T10:04:00Z"));
        assertFalse(message.contains("独立灾备介质回读摘要不一致"));
        assertFalse(message.contains(candidate.record().tombstoneId().toString()));
    }

    @Test
    void shouldNotCallExternalStorageWhenConcurrentWorkerPreparedFirst() {
        DeletionTombstoneExportCandidate candidate = candidate(null, 0);
        EncryptedDeletionTombstoneEnvelope envelope = envelope();
        when(repositoryPort.listReady(NOW, 10)).thenReturn(List.of(candidate));
        when(codecPort.seal(candidate.record())).thenReturn(envelope);
        when(repositoryPort.prepare(candidate.record().tombstoneId(), envelope, NOW))
                .thenReturn(false);
        DeletionTombstoneExportWorker worker = worker(enabledProperties());

        assertEquals(0, worker.processReady());

        verifyNoInteractions(exportPort);
    }

    private DeletionTombstoneExportWorker worker(DisasterRecoveryProperties properties) {
        return new DeletionTombstoneExportWorker(
                properties,
                repositoryPort,
                codecPort,
                exportPort,
                digestService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private DisasterRecoveryProperties enabledProperties() {
        return new DisasterRecoveryProperties(
                false,
                "",
                true,
                "dr-key-v1",
                Base64.getEncoder().encodeToString(new byte[32]));
    }

    private DisasterRecoveryProperties disabledProperties() {
        return new DisasterRecoveryProperties(false, "", false, "", "");
    }

    private DeletionTombstoneExportCandidate candidate(
            EncryptedDeletionTombstoneEnvelope envelope,
            int retryCount) {
        Instant acceptedAt = NOW.minusSeconds(80 * 60 * 60);
        DeletionTombstoneExportRecord record = new DeletionTombstoneExportRecord(
                UUID.randomUUID(),
                new byte[32],
                2L,
                acceptedAt,
                acceptedAt.plusSeconds(60),
                acceptedAt.plusSeconds(72 * 60 * 60),
                "deletion-tombstone-v1",
                NOW.plusSeconds(37 * 24 * 60 * 60));
        return new DeletionTombstoneExportCandidate(record, envelope, retryCount);
    }

    private EncryptedDeletionTombstoneEnvelope envelope() {
        byte[] cipher = new byte[80];
        byte[] hash = digestService.sha256(cipher);
        return new EncryptedDeletionTombstoneEnvelope("dr-key-v1", cipher, hash);
    }
}
