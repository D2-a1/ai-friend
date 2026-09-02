package com.aifriend.retention.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.shared.logging.TestLogCapture;

class TaskHistoryDeletionWorkerTest {
    private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");
    private static final String SENSITIVE_FAILURE_DETAIL = "storage-secret-detail";

    @Test void shouldCompleteOnlyAfterStorageVerification() {
        RecordingRepository repository = new RecordingRepository();
        RecordingStorage storage = new RecordingStorage();
        TaskHistoryDeletionWorker worker = new TaskHistoryDeletionWorker(repository, storage, Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(0, worker.processReady());
        assertEquals(0, repository.completed);
        storage.cleared = true;
        assertEquals(1, worker.processReady());
        assertEquals(1, repository.completed);
    }

    @Test void shouldRetryWithoutCompletingWhenStorageDeletionFails() {
        RecordingRepository repository = new RecordingRepository();
        TaskHistoryStoragePort storage = new TaskHistoryStoragePort() {
            @Override public int cleanupBatch(UUID owner, Instant cutoff, int batchSize) {
                throw new IllegalStateException(SENSITIVE_FAILURE_DETAIL);
            }
            @Override public boolean isCleared(UUID owner, Instant cutoff) { return false; }
        };
        TaskHistoryDeletionWorker worker = new TaskHistoryDeletionWorker(
                repository, storage, Clock.fixed(NOW, ZoneOffset.UTC));

        int completed;
        List<String> messages;
        try (TestLogCapture capture = TestLogCapture.forClass(
                TaskHistoryDeletionWorker.class)) {
            completed = worker.processReady();
            messages = capture.messages();
        }

        assertEquals(0, completed);
        assertEquals(0, repository.completed);
        assertEquals(1, repository.retried);
        assertEquals(NOW.plusSeconds(30), repository.nextAttemptAt);
        assertEquals(1, messages.size());
        String message = messages.get(0);
        assertTrue(message.contains("stage=TASK_HISTORY_DELETION"));
        assertTrue(message.contains("errorType=IllegalStateException"));
        assertTrue(message.contains("retryCount=0"));
        assertTrue(message.contains("nextAttemptAt=2026-08-19T10:00:30Z"));
        assertFalse(message.contains(SENSITIVE_FAILURE_DETAIL));
        assertFalse(message.contains(repository.id.toString()));
        assertFalse(message.contains(repository.owner.toString()));
    }

    private static final class RecordingStorage implements TaskHistoryStoragePort {
        private boolean cleared;
        @Override public int cleanupBatch(UUID owner, Instant cutoff, int batchSize) { return 1; }
        @Override public boolean isCleared(UUID owner, Instant cutoff) { return cleared; }
    }
    private static final class RecordingRepository implements TaskHistoryDeletionRepositoryPort {
        private final UUID id=UUID.randomUUID(); private final UUID owner=UUID.randomUUID();
        private int completed; private int retried; private Instant nextAttemptAt;
        @Override public List<TaskHistoryDeletionJob> findReady(Instant now,int batchSize){return List.of(new TaskHistoryDeletionJob(id,owner,NOW,0));}
        @Override public void markCompleted(UUID jobId,Instant now){completed++;}
        @Override public TaskHistoryDeletionView accept(UUID owner,byte[] key,byte[] request,Instant now){throw new UnsupportedOperationException();}
        @Override public Optional<TaskHistoryDeletionView> findLatest(UUID owner){return Optional.empty();}
        @Override public void markRetry(UUID id,Instant attemptedAt,Instant nextAttemptAt){retried++;this.nextAttemptAt=nextAttemptAt;}
    }
}
