package com.aifriend.retention.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;

class TaskHistoryDeletionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");

    @Test void shouldAcceptAndQueryLatestDeletion() {
        MemoryRepository repository = new MemoryRepository();
        TaskHistoryDeletionService service = new TaskHistoryDeletionService(repository, new DigestService(), Clock.fixed(NOW, ZoneOffset.UTC));
        UUID owner = UUID.randomUUID();
        TaskHistoryDeletionView accepted = service.clear(owner, "01JHISTORYDELETE000000000001");
        assertEquals("CLEARING", accepted.status());
        assertEquals(accepted, service.get(owner));
    }

    @Test void shouldReturnNotFoundWithoutDeletion() {
        TaskHistoryDeletionService service = new TaskHistoryDeletionService(new MemoryRepository(), new DigestService(), Clock.fixed(NOW, ZoneOffset.UTC));
        BusinessException exception = assertThrows(BusinessException.class, () -> service.get(UUID.randomUUID()));
        assertEquals(ErrorCode.NOT_FOUND, exception.errorCode());
    }

    private static final class MemoryRepository implements TaskHistoryDeletionRepositoryPort {
        private TaskHistoryDeletionView latest;
        @Override public TaskHistoryDeletionView accept(UUID owner, byte[] key, byte[] request, Instant now) { latest = new TaskHistoryDeletionView("CLEARING", now, null); return latest; }
        @Override public Optional<TaskHistoryDeletionView> findLatest(UUID owner) { return Optional.ofNullable(latest); }
        @Override public List<TaskHistoryDeletionJob> findReady(Instant now, int batchSize) { return new ArrayList<>(); }
        @Override public void markRetry(UUID id, Instant attemptedAt, Instant nextAttemptAt) { }
        @Override public void markCompleted(UUID id, Instant now) { }
    }
}