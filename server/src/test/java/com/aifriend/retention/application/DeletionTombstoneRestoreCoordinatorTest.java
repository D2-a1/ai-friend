package com.aifriend.retention.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

class DeletionTombstoneRestoreCoordinatorTest {

    private static final String SNAPSHOT_ID = "snapshot-20260820";
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private DeletionTombstoneRestoreSourcePort sourcePort;
    private DeletionTombstoneRestoreRepositoryPort repositoryPort;
    private DeletionTombstoneEnvelopeCodecPort codecPort;
    private DigestService digestService;

    @BeforeEach
    void setUp() {
        sourcePort = mock(DeletionTombstoneRestoreSourcePort.class);
        repositoryPort = mock(DeletionTombstoneRestoreRepositoryPort.class);
        codecPort = mock(DeletionTombstoneEnvelopeCodecPort.class);
        digestService = new DigestService();
    }

    @Test
    void shouldReplayAllBatchesAndRecordCurrentBootVerification() {
        DeletionTombstoneRestoreEntry first = entry(1);
        DeletionTombstoneRestoreEntry second = entry(2);
        DeletionTombstoneExportRecord firstRecord = record(first.tombstoneId(), 1);
        DeletionTombstoneExportRecord secondRecord = record(second.tombstoneId(), 2);
        DeletionTombstoneRestoreManifest manifest = manifest(List.of(first, second));
        when(sourcePort.openSnapshot(SNAPSHOT_ID)).thenReturn(manifest);
        when(sourcePort.readBatch(SNAPSHOT_ID, null, 100))
                .thenReturn(new DeletionTombstoneRestoreBatch(List.of(first), "next-1", false));
        when(sourcePort.readBatch(SNAPSHOT_ID, "next-1", 100))
                .thenReturn(new DeletionTombstoneRestoreBatch(List.of(second), null, true));
        when(codecPort.open(first.envelope())).thenReturn(firstRecord);
        when(codecPort.open(second.envelope())).thenReturn(secondRecord);
        when(repositoryPort.countResurrectedAccounts()).thenReturn(0L);

        DeletionTombstoneRestoreReport report = coordinator().restoreAndVerify();

        assertEquals(2L, report.replayedItemCount());
        verify(repositoryPort).replayBatch(List.of(firstRecord), NOW);
        verify(repositoryPort).replayBatch(List.of(secondRecord), NOW);
        verify(repositoryPort).recordVerification(
                eq(digestService.sha256(SNAPSHOT_ID)),
                eq(DeletionTombstoneRestoreDigest.manifestHash(manifest)),
                eq(2L),
                eq(2L),
                eq(manifest.sourceProofHash()),
                eq(NOW));
    }

    @Test
    void shouldRejectObjectWhoseEnvelopeBindsAnotherTombstone() {
        DeletionTombstoneRestoreEntry entry = entry(1);
        DeletionTombstoneRestoreManifest manifest = manifest(List.of(entry));
        when(sourcePort.openSnapshot(SNAPSHOT_ID)).thenReturn(manifest);
        when(sourcePort.readBatch(SNAPSHOT_ID, null, 100))
                .thenReturn(new DeletionTombstoneRestoreBatch(List.of(entry), null, true));
        when(codecPort.open(entry.envelope())).thenReturn(record(uuid(2), 1));

        assertThrows(IllegalStateException.class, () -> coordinator().restoreAndVerify());

        verify(repositoryPort, never()).replayBatch(any(), any());
        verify(repositoryPort, never()).recordVerification(
                any(), any(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void shouldRejectRepeatedPaginationCursorBeforeManifestCompletion() {
        DeletionTombstoneRestoreEntry first = entry(1);
        DeletionTombstoneRestoreEntry second = entry(2);
        DeletionTombstoneRestoreEntry third = entry(3);
        when(sourcePort.openSnapshot(SNAPSHOT_ID))
                .thenReturn(manifest(List.of(first, second, third)));
        when(sourcePort.readBatch(SNAPSHOT_ID, null, 100))
                .thenReturn(new DeletionTombstoneRestoreBatch(List.of(first), "loop", false));
        when(sourcePort.readBatch(SNAPSHOT_ID, "loop", 100))
                .thenReturn(new DeletionTombstoneRestoreBatch(List.of(second), "loop", false));
        when(codecPort.open(first.envelope())).thenReturn(record(first.tombstoneId(), 1));
        when(codecPort.open(second.envelope())).thenReturn(record(second.tombstoneId(), 2));

        assertThrows(IllegalStateException.class, () -> coordinator().restoreAndVerify());

        verify(repositoryPort, never()).recordVerification(
                any(), any(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void shouldRejectManifestAggregateMismatchAfterIdempotentReplay() {
        DeletionTombstoneRestoreEntry entry = entry(1);
        byte[] wrongAggregate = new byte[32];
        wrongAggregate[0] = 1;
        DeletionTombstoneRestoreManifest manifest = new DeletionTombstoneRestoreManifest(
                SNAPSHOT_ID, 1L, wrongAggregate, new byte[32]);
        when(sourcePort.openSnapshot(SNAPSHOT_ID)).thenReturn(manifest);
        when(sourcePort.readBatch(SNAPSHOT_ID, null, 100))
                .thenReturn(new DeletionTombstoneRestoreBatch(List.of(entry), null, true));
        when(codecPort.open(entry.envelope())).thenReturn(record(entry.tombstoneId(), 1));

        assertThrows(IllegalStateException.class, () -> coordinator().restoreAndVerify());

        verify(repositoryPort).replayBatch(any(), eq(NOW));
        verify(repositoryPort, never()).countResurrectedAccounts();
    }

    @Test
    void shouldRejectOldGenerationAccountResurrectionWithoutDeletingIt() {
        DeletionTombstoneRestoreEntry entry = entry(1);
        when(sourcePort.openSnapshot(SNAPSHOT_ID)).thenReturn(manifest(List.of(entry)));
        when(sourcePort.readBatch(SNAPSHOT_ID, null, 100))
                .thenReturn(new DeletionTombstoneRestoreBatch(List.of(entry), null, true));
        when(codecPort.open(entry.envelope())).thenReturn(record(entry.tombstoneId(), 1));
        when(repositoryPort.countResurrectedAccounts()).thenReturn(1L);

        assertThrows(IllegalStateException.class, () -> coordinator().restoreAndVerify());

        verify(repositoryPort, never()).recordVerification(
                any(), any(), anyLong(), anyLong(), any(), any());
    }

    private DeletionTombstoneRestoreCoordinator coordinator() {
        return new DeletionTombstoneRestoreCoordinator(
                restoreProperties(),
                sourcePort,
                repositoryPort,
                codecPort,
                digestService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private DisasterRecoveryProperties restoreProperties() {
        return new DisasterRecoveryProperties(
                true,
                SNAPSHOT_ID,
                false,
                "dr-key-v1",
                Base64.getEncoder().encodeToString(new byte[32]));
    }

    private DeletionTombstoneRestoreManifest manifest(
            List<DeletionTombstoneRestoreEntry> entries) {
        DeletionTombstoneRestoreDigest aggregate =
                new DeletionTombstoneRestoreDigest(SNAPSHOT_ID, entries.size());
        entries.forEach(aggregate::update);
        return new DeletionTombstoneRestoreManifest(
                SNAPSHOT_ID, entries.size(), aggregate.finish(), new byte[32]);
    }

    private DeletionTombstoneRestoreEntry entry(int sequence) {
        byte[] cipher = new byte[80];
        cipher[cipher.length - 1] = (byte) sequence;
        EncryptedDeletionTombstoneEnvelope envelope = new EncryptedDeletionTombstoneEnvelope(
                "dr-key-v1", cipher, digestService.sha256(cipher));
        return new DeletionTombstoneRestoreEntry(uuid(sequence), envelope);
    }

    private DeletionTombstoneExportRecord record(UUID tombstoneId, int sequence) {
        byte[] subjectHash = new byte[32];
        subjectHash[0] = (byte) sequence;
        return new DeletionTombstoneExportRecord(
                tombstoneId,
                subjectHash,
                sequence,
                NOW.minusSeconds(10_000),
                NOW.minusSeconds(9_000),
                NOW.minusSeconds(8_000),
                "deletion-tombstone-v1",
                NOW.plusSeconds(10_000));
    }

    private UUID uuid(int sequence) {
        return UUID.fromString("00000000-0000-0000-0000-00000000000" + sequence);
    }
}
