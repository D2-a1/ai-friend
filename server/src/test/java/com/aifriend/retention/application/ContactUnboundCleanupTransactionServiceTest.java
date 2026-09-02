package com.aifriend.retention.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ContactUnboundCleanupTransactionServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-08-25T10:00:00Z");

    @Test
    void shouldReturnFalseWithoutReadyEvent() {
        RecordingRepository repository = new RecordingRepository();
        ContactUnboundCleanupTransactionService service = service(repository);

        assertFalse(service.processNext());
        assertTrue(repository.calls.isEmpty());
    }

    @Test
    void shouldScrubCancelAndCompleteInOrder() {
        RecordingRepository repository = new RecordingRepository();
        repository.job = new ContactUnboundCleanupJob(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        ContactUnboundCleanupTransactionService service = service(repository);

        assertTrue(service.processNext());
        assertTrue(repository.calls.equals(
                List.of("scrubAliases", "cancelTasks", "markCompleted")));
    }

    @Test
    void shouldNotCompleteEventWhenTaskCancellationFails() {
        RecordingRepository repository = new RecordingRepository();
        repository.job = new ContactUnboundCleanupJob(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        repository.failCancellation = true;
        ContactUnboundCleanupTransactionService service = service(repository);

        assertThrows(IllegalStateException.class, service::processNext);
        assertTrue(repository.calls.equals(
                List.of("scrubAliases", "cancelTasks")));
    }

    private ContactUnboundCleanupTransactionService service(
            ContactUnboundCleanupRepositoryPort repository) {
        return new ContactUnboundCleanupTransactionService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class RecordingRepository
            implements ContactUnboundCleanupRepositoryPort {

        private final List<String> calls = new ArrayList<>();
        private ContactUnboundCleanupJob job;
        private boolean failCancellation;

        @Override
        public Optional<ContactUnboundCleanupJob> lockNextReady(Instant now) {
            return Optional.ofNullable(job);
        }

        @Override
        public int scrubAliases(
                UUID ownerUserId,
                UUID contactId,
                Instant now) {
            calls.add("scrubAliases");
            return 1;
        }

        @Override
        public int cancelNonTerminalTasks(
                UUID ownerUserId,
                UUID contactId,
                Instant now) {
            calls.add("cancelTasks");
            if (failCancellation) {
                throw new IllegalStateException("task cleanup unavailable");
            }
            return 1;
        }

        @Override
        public void markCompleted(UUID eventId) {
            calls.add("markCompleted");
        }
    }
}
