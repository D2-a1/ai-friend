package com.aifriend.template.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;

class RoutineCommandDeletionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-23T04:00:00Z");
    private static final String IDEMPOTENCY_KEY = "01JROUTINEDELETE00000000001";

    @Test
    void shouldDeleteOnlyCurrentOwnerTemplatesAndRecordAudit() {
        UUID ownerUserId = UUID.randomUUID();
        RoutineCommandTemplateStorePort storePort = mock(RoutineCommandTemplateStorePort.class);
        AuditEventPort auditEventPort = mock(AuditEventPort.class);
        RoutineCommandLearningQueuePort queuePort = mock(RoutineCommandLearningQueuePort.class);
        when(storePort.lockNamespace(ownerUserId, NOW)).thenReturn(4L);
        when(storePort.findDeletion(eq(ownerUserId), any())).thenReturn(Optional.empty());
        when(storePort.countByOwner(ownerUserId)).thenReturn(3);
        when(storePort.deleteAllByOwner(ownerUserId)).thenReturn(3);
        RoutineCommandDeletionService service = new RoutineCommandDeletionService(
                storePort, queuePort, new DigestService(), auditEventPort,
                Clock.fixed(NOW, ZoneOffset.UTC));

        RoutineCommandDeletionView result = service.deleteAll(
                ownerUserId, IDEMPOTENCY_KEY,
                new RoutineCommandDeletionCommand(true, 4L));

        assertEquals(3, result.deletedCount());
        assertEquals(NOW, result.deletedAt());
        verify(storePort).updateNamespace(ownerUserId, 4L, 5L, NOW);
        verify(storePort).saveDeletion(
                eq(ownerUserId), any(), any(), eq(4L), eq(3), eq(5L), eq(NOW));
        verify(queuePort).cancelOutstandingByOwner(
                ownerUserId, "USER_CLEARED", NOW);
        verify(auditEventPort).append(
                ownerUserId, "ROUTINE_COMMAND_DELETE_ALL", "SUCCESS", null, NOW);
    }

    @Test
    void shouldReplaySameRequestWithoutDeletingAgain() {
        UUID ownerUserId = UUID.randomUUID();
        DigestService digestService = new DigestService();
        RoutineCommandTemplateStorePort storePort = mock(RoutineCommandTemplateStorePort.class);
        when(storePort.lockNamespace(ownerUserId, NOW)).thenReturn(7L);
        when(storePort.findDeletion(eq(ownerUserId), any())).thenReturn(Optional.of(
                new RoutineCommandDeletionRecord(
                        digestService.sha256(
                                new RoutineCommandDeletionCommand(true, null)
                                        .fingerprintInput()),
                        2, NOW.minusSeconds(30), 7L)));
        RoutineCommandDeletionService service = new RoutineCommandDeletionService(
                storePort, mock(RoutineCommandLearningQueuePort.class),
                digestService, mock(AuditEventPort.class),
                Clock.fixed(NOW, ZoneOffset.UTC));

        RoutineCommandDeletionView result = service.deleteAll(
                ownerUserId, IDEMPOTENCY_KEY,
                new RoutineCommandDeletionCommand(true, null));

        assertEquals(2, result.deletedCount());
        assertEquals(NOW.minusSeconds(30), result.deletedAt());
        verify(storePort, never()).deleteAllByOwner(any());
        verify(storePort, never()).updateNamespace(any(), any(Long.class), any(Long.class), any());
    }

    @Test
    void shouldRejectVersionConflictBeforeDeletion() {
        UUID ownerUserId = UUID.randomUUID();
        RoutineCommandTemplateStorePort storePort = mock(RoutineCommandTemplateStorePort.class);
        when(storePort.lockNamespace(ownerUserId, NOW)).thenReturn(2L);
        when(storePort.findDeletion(eq(ownerUserId), any())).thenReturn(Optional.empty());
        RoutineCommandDeletionService service = service(
                storePort, mock(AuditEventPort.class));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.deleteAll(
                        ownerUserId, IDEMPOTENCY_KEY,
                        new RoutineCommandDeletionCommand(true, 1L)));

        assertEquals(ErrorCode.SESSION_CONFLICT, exception.errorCode());
        verify(storePort, never()).deleteAllByOwner(any());
    }

    @Test
    void shouldRejectUnconfirmedRequest() {
        RoutineCommandDeletionService service = service(
                mock(RoutineCommandTemplateStorePort.class),
                mock(AuditEventPort.class));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.deleteAll(
                        UUID.randomUUID(), IDEMPOTENCY_KEY,
                        new RoutineCommandDeletionCommand(false, null)));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.errorCode());
    }

    private RoutineCommandDeletionService service(
            RoutineCommandTemplateStorePort storePort,
            AuditEventPort auditEventPort) {
        return new RoutineCommandDeletionService(
                storePort, mock(RoutineCommandLearningQueuePort.class),
                new DigestService(), auditEventPort,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
