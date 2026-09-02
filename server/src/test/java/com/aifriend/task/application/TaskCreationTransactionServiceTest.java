package com.aifriend.task.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.voice.application.AudioObjectConsumptionTransactionService;
import com.aifriend.voice.application.ValidatedAudioObject;

class TaskCreationTransactionServiceTest {

    @Test
    void shouldRejectNewTaskBeforeAudioConsumptionWhileHistoryIsClearing() {
        TaskSessionRepositoryPort repository = mock(TaskSessionRepositoryPort.class);
        AudioObjectConsumptionTransactionService audioTransaction =
                mock(AudioObjectConsumptionTransactionService.class);
        TaskHistoryClearingGuardPort clearingGuard = mock(TaskHistoryClearingGuardPort.class);
        TaskSessionMapper mapper = mock(TaskSessionMapper.class);
        TaskCreationTransactionService service = new TaskCreationTransactionService(
                repository, audioTransaction, new DigestService(), clearingGuard, mapper);
        TaskStoredSession candidate = mock(TaskStoredSession.class);
        ValidatedAudioObject audio = mock(ValidatedAudioObject.class);
        UUID owner = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-20T08:00:00Z");
        when(candidate.ownerUserId()).thenReturn(owner);
        when(candidate.createdAt()).thenReturn(createdAt);
        when(clearingGuard.isClearing(owner)).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.create(candidate, List.of(), audio, null));

        assertEquals(ErrorCode.SESSION_CONFLICT, exception.errorCode());
        verify(repository).lockNamespace(owner, createdAt);
        verify(repository, never()).findByCreateKey(owner, null);
        verifyNoInteractions(audioTransaction, mapper);
    }

    @Test
    void shouldRejectContinuationWhenAnotherTaskBecameLatest() {
        TaskSessionRepositoryPort repository = mock(TaskSessionRepositoryPort.class);
        AudioObjectConsumptionTransactionService audioTransaction =
                mock(AudioObjectConsumptionTransactionService.class);
        TaskHistoryClearingGuardPort clearingGuard = mock(TaskHistoryClearingGuardPort.class);
        TaskSessionMapper mapper = mock(TaskSessionMapper.class);
        TaskCreationTransactionService service = new TaskCreationTransactionService(
                repository, audioTransaction, new DigestService(), clearingGuard, mapper);
        TaskStoredSession candidate = mock(TaskStoredSession.class);
        TaskStoredSession latest = mock(TaskStoredSession.class);
        ValidatedAudioObject audio = mock(ValidatedAudioObject.class);
        UUID owner = UUID.randomUUID();
        UUID expectedSource = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-22T08:00:00Z");
        when(candidate.ownerUserId()).thenReturn(owner);
        when(candidate.createdAt()).thenReturn(createdAt);
        when(candidate.createIdempotencyKeyHash()).thenReturn(new byte[32]);
        when(clearingGuard.isClearing(owner)).thenReturn(false);
        when(repository.findLatestByOwner(owner)).thenReturn(java.util.Optional.of(latest));
        when(latest.id()).thenReturn(UUID.randomUUID());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.create(candidate, List.of(), audio, expectedSource));

        assertEquals(ErrorCode.SESSION_CONFLICT, exception.errorCode());
        verifyNoInteractions(audioTransaction, mapper);
    }
}
