package com.aifriend.template.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.task.domain.TaskIntent;

class RoutineCommandLearningTransactionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

    private RoutineCommandLearningQueuePort queuePort;
    private RoutineCommandTemplateStorePort storePort;
    private AuditEventPort auditEventPort;
    private RoutineCommandLearningTransactionService service;
    private RoutineCommandLearningJob job;

    @BeforeEach
    void setUp() {
        queuePort = mock(RoutineCommandLearningQueuePort.class);
        storePort = mock(RoutineCommandTemplateStorePort.class);
        auditEventPort = mock(AuditEventPort.class);
        service = new RoutineCommandLearningTransactionService(
                queuePort, storePort, auditEventPort,
                Clock.fixed(NOW, ZoneOffset.UTC));
        job = job(1, NOW.plusSeconds(3_600));
    }

    @Test
    void expiredSourceMustBeSkippedBeforeExternalProcessing() {
        job = job(1, NOW);
        when(storePort.lockNamespace(job.ownerUserId(), NOW)).thenReturn(4L);
        when(queuePort.findClaimedForUpdate(job.id(), job.leaseToken()))
                .thenReturn(Optional.of(job));

        Optional<RoutineCommandTemplateSnapshot> result = service.snapshot(job);

        assertTrue(result.isEmpty());
        verify(queuePort).markSkipped(
                job.id(), job.leaseToken(), "SOURCE_EXPIRED", NOW);
        verify(storePort, never()).findByOwnerAndIntent(any(), any());
    }

    @Test
    void duplicateTemplateMustOnlyIncrementUsageAndAdvanceNamespace() {
        UUID templateId = UUID.randomUUID();
        when(storePort.lockNamespace(job.ownerUserId(), NOW)).thenReturn(5L);
        when(queuePort.findClaimedForUpdate(job.id(), job.leaseToken()))
                .thenReturn(Optional.of(job));

        service.apply(job, 5L,
                new RoutineCommandTemplateMatch(templateId, 7L, 0.2D), null);

        verify(storePort).incrementUsage(job.ownerUserId(), templateId, 7L, NOW);
        verify(storePort, never()).insert(any(), any());
        verify(storePort).updateNamespace(job.ownerUserId(), 5L, 6L, NOW);
        verify(queuePort).markDone(job.id(), job.leaseToken(), NOW);
        verify(auditEventPort).append(
                job.ownerUserId(), "ROUTINE_COMMAND_LEARN", "SUCCESS", null, NOW);
    }

    @Test
    void fullNamespaceMustEvictLeastUsedOldestTemplateDeterministically() {
        UUID victimId = UUID.randomUUID();
        List<RoutineCommandTemplateRecord> templates = new ArrayList<>();
        templates.add(record(victimId, 0, NOW.minusSeconds(600), 2L));
        for (int index = 1; index < 30; index++) {
            templates.add(record(
                    UUID.randomUUID(), index, NOW.minusSeconds(300 - index), 1L));
        }
        when(storePort.lockNamespace(job.ownerUserId(), NOW)).thenReturn(9L);
        when(queuePort.findClaimedForUpdate(job.id(), job.leaseToken()))
                .thenReturn(Optional.of(job));
        when(storePort.findAllByOwner(job.ownerUserId())).thenReturn(templates);
        RoutineCommandTemplateWrite write = new RoutineCommandTemplateWrite(
                UUID.randomUUID(), TaskIntent.VOICE_CALL, "wugang",
                "dialect-v1", "mfcc-v1", "threshold-v1",
                new byte[32], new byte[32], NOW);

        service.apply(job, 9L, null, write);

        verify(storePort).deleteExact(job.ownerUserId(), victimId, 2L);
        verify(storePort).insert(job.ownerUserId(), write);
        verify(storePort).updateNamespace(job.ownerUserId(), 9L, 10L, NOW);
    }

    @Test
    void namespaceChangeMustRejectStaleExternalResultWithoutMutation() {
        when(storePort.lockNamespace(job.ownerUserId(), NOW)).thenReturn(11L);
        RoutineCommandTemplateWrite write = new RoutineCommandTemplateWrite(
                UUID.randomUUID(), TaskIntent.VOICE_CALL, "wugang",
                "dialect-v1", "mfcc-v1", "threshold-v1",
                new byte[32], new byte[32], NOW);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.apply(job, 10L, null, write));

        assertEquals(ErrorCode.SESSION_CONFLICT, exception.errorCode());
        verify(storePort, never()).insert(any(), any());
        verify(storePort, never()).incrementUsage(any(), any(), any(Long.class), any());
        verify(queuePort, never()).markDone(any(), any(), any());
    }

    @Test
    void exhaustedFailureMustBeSkippedInsteadOfRetried() {
        job = job(8, NOW.plusSeconds(3_600));
        when(queuePort.findClaimedForUpdate(job.id(), job.leaseToken()))
                .thenReturn(Optional.of(job));

        service.settleFailure(job, "LOCAL_PROCESSING_FAILED", false);

        verify(queuePort).markSkipped(
                job.id(), job.leaseToken(), "LOCAL_PROCESSING_FAILED", NOW);
        verify(queuePort, never()).markRetry(any(), any(), any(), any(), any());
    }

    private RoutineCommandLearningJob job(int attempts, Instant retentionUntil) {
        return new RoutineCommandLearningJob(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), TaskIntent.VOICE_CALL, 500, 900,
                "wugang", "dialect-v1", "mfcc-v1", "threshold-v1",
                attempts, UUID.randomUUID(), retentionUntil,
                NOW.minusSeconds(60));
    }

    private RoutineCommandTemplateRecord record(
            UUID id,
            int usageCount,
            Instant lastConfirmedAt,
            long version) {
        return new RoutineCommandTemplateRecord(
                id, TaskIntent.VOICE_CALL, "wugang", "dialect-v1",
                "mfcc-v1", "threshold-v1", new byte[32], new byte[32],
                usageCount, lastConfirmedAt, version, lastConfirmedAt);
    }
}
