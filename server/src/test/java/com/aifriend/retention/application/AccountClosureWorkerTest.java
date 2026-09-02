package com.aifriend.retention.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.shared.logging.TestLogCapture;

class AccountClosureWorkerTest {
    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");
    private static final String SENSITIVE_FAILURE_DETAIL = "storage-secret-detail";

    @Test
    void shouldPublishAlertsAndCompleteOnlyAfterLockedVerification() {
        RecordingJobs jobs = new RecordingJobs(0);
        RecordingCleanup cleanup = new RecordingCleanup(true, false);
        AccountClosureWorker worker = worker(jobs, cleanup);

        int completed = worker.processReady();

        assertEquals(1, completed);
        assertEquals(1, jobs.alertCalls);
        assertEquals(50, jobs.alertBatchSize);
        assertEquals(1, cleanup.cleanupCalls);
        assertEquals(1, cleanup.finalizeCalls);
        assertEquals(0, jobs.retryCalls);
    }

    @Test
    void shouldKeepAcceptedWhenStorageVerificationIsNotYetClear() {
        RecordingJobs jobs = new RecordingJobs(0);
        RecordingCleanup cleanup = new RecordingCleanup(false, false);
        AccountClosureWorker worker = worker(jobs, cleanup);

        int completed = worker.processReady();

        assertEquals(0, completed);
        assertEquals(1, cleanup.cleanupCalls);
        assertEquals(1, cleanup.finalizeCalls);
        assertEquals(0, jobs.retryCalls);
    }

    @Test
    void shouldBackOffWithoutWritingCompletionWhenCleanupFails() {
        RecordingJobs jobs = new RecordingJobs(3);
        RecordingCleanup cleanup = new RecordingCleanup(false, true);
        AccountClosureWorker worker = worker(jobs, cleanup);

        int completed;
        List<String> messages;
        try (TestLogCapture capture = TestLogCapture.forClass(AccountClosureWorker.class)) {
            completed = worker.processReady();
            messages = capture.messages();
        }

        assertEquals(0, completed);
        assertEquals(1, jobs.retryCalls);
        assertEquals(NOW, jobs.attemptedAt);
        assertEquals(NOW.plusSeconds(240), jobs.nextAttemptAt);
        assertEquals(0, cleanup.finalizeCalls);
        assertEquals(1, messages.size());
        String message = messages.get(0);
        assertTrue(message.contains("stage=ACCOUNT_CLOSURE_CLEANUP"));
        assertTrue(message.contains("errorType=IllegalStateException"));
        assertTrue(message.contains("retryCount=3"));
        assertTrue(message.contains("nextAttemptAt=2026-08-20T10:04:00Z"));
        assertFalse(message.contains(SENSITIVE_FAILURE_DETAIL));
        assertFalse(message.contains(jobs.job.id().toString()));
        assertFalse(message.contains(jobs.job.ownerUserId().toString()));
    }

    private AccountClosureWorker worker(
            AccountClosureJobRepositoryPort jobs,
            AccountClosureCleanupPort cleanup) {
        return new AccountClosureWorker(
                jobs, cleanup, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class RecordingJobs implements AccountClosureJobRepositoryPort {
        private final AccountClosureJob job;
        private int alertCalls;
        private int alertBatchSize;
        private int retryCalls;
        private Instant attemptedAt;
        private Instant nextAttemptAt;

        private RecordingJobs(int retryCount) {
            job = new AccountClosureJob(
                    UUID.randomUUID(), UUID.randomUUID(), NOW.minusSeconds(60), retryCount);
        }

        @Override
        public List<AccountClosureJob> findReady(Instant now, int batchSize) {
            assertEquals(NOW, now);
            assertEquals(10, batchSize);
            return List.of(job);
        }

        @Override
        public void markRetry(UUID jobId, Instant attempted, Instant nextAttempt) {
            assertEquals(job.id(), jobId);
            retryCalls++;
            attemptedAt = attempted;
            nextAttemptAt = nextAttempt;
        }

        @Override
        public int publishDueAlerts(Instant now, int batchSize) {
            assertEquals(NOW, now);
            alertCalls++;
            alertBatchSize = batchSize;
            return 0;
        }
    }

    private static final class RecordingCleanup implements AccountClosureCleanupPort {
        private final boolean cleared;
        private final boolean failCleanup;
        private int cleanupCalls;
        private int finalizeCalls;

        private RecordingCleanup(boolean cleared, boolean failCleanup) {
            this.cleared = cleared;
            this.failCleanup = failCleanup;
        }

        @Override
        public int cleanupBatch(UUID ownerUserId, int batchSize) {
            cleanupCalls++;
            assertEquals(50, batchSize);
            if (failCleanup) {
                throw new IllegalStateException(SENSITIVE_FAILURE_DETAIL);
            }
            return 1;
        }

        @Override
        public boolean finalizeIfCleared(UUID jobId, UUID ownerUserId, Instant now) {
            finalizeCalls++;
            assertEquals(NOW, now);
            return cleared;
        }
    }
}
