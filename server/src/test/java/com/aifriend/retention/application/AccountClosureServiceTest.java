package com.aifriend.retention.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.shared.security.DigestService;

class AccountClosureServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-20T02:00:00Z");

    @Test
    void shouldBindHashesAndReturnSeventyTwoHourBoundary() {
        RecordingRepository repository = new RecordingRepository();
        DigestService digestService = new DigestService();
        AccountClosureService service = new AccountClosureService(
                repository, digestService, Clock.fixed(NOW, ZoneOffset.UTC));
        UUID ownerUserId = UUID.randomUUID();

        AccountClosureView view = service.close(
                ownerUserId, "01JACCOUNTDELETE000000000001", 7L);

        assertEquals(ownerUserId, repository.ownerUserId);
        assertArrayEquals(
                digestService.sha256("01JACCOUNTDELETE000000000001"),
                repository.idempotencyKeyHash);
        assertArrayEquals(
                digestService.sha256("account-closure-v1:confirmed=true;expectedVersion=7"),
                repository.requestHash);
        assertEquals(7L, repository.expectedVersion);
        assertEquals(NOW, view.acceptedAt());
        assertEquals(NOW.plusSeconds(72 * 60 * 60), view.reRegistrationNotBefore());
    }

    private static final class RecordingRepository implements AccountClosureRepositoryPort {
        private UUID ownerUserId;
        private byte[] idempotencyKeyHash;
        private byte[] requestHash;
        private Long expectedVersion;

        @Override
        public AccountClosureView accept(
                UUID owner,
                byte[] keyHash,
                byte[] bodyHash,
                Long accountVersion,
                Instant acceptedAt,
                Instant reRegistrationNotBefore) {
            ownerUserId = owner;
            idempotencyKeyHash = keyHash;
            requestHash = bodyHash;
            expectedVersion = accountVersion;
            return new AccountClosureView(acceptedAt, reRegistrationNotBefore);
        }
    }
}
