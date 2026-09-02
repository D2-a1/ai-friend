package com.aifriend.consent.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.consent.domain.ConsentDecision;
import com.aifriend.consent.domain.ConsentRecord;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;

class ConsentServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

    @Test
    void shouldAppendHistoryAndReturnOnlyLatestDecision() {
        InMemoryConsentRecordPort records = new InMemoryConsentRecordPort();
        CountingOutboxPort outbox = new CountingOutboxPort();
        ConsentService service = service(records, outbox);
        UUID userId = UUID.randomUUID();

        service.update(
                userId,
                ConsentType.TASK_AUDIO,
                ConsentDecision.GRANTED,
                "privacy-v1",
                NOW,
                "01JCONSENTGRANTED0000000000");
        ConsentRecord revoked = service.update(
                userId,
                ConsentType.TASK_AUDIO,
                ConsentDecision.REVOKED,
                "privacy-v1",
                NOW.plusSeconds(1),
                "01JCONSENTREVOKED0000000000");

        List<ConsentRecord> current = service.listCurrent(userId);
        assertEquals(2, records.records.size());
        assertEquals(1, current.size());
        assertEquals(revoked.id(), current.get(0).id());
        assertEquals(1, outbox.count);
    }

    @Test
    void shouldReturnOriginalForSameIdempotentRequestAndRejectDifferentRequest() {
        InMemoryConsentRecordPort records = new InMemoryConsentRecordPort();
        ConsentService service = service(records, new CountingOutboxPort());
        UUID userId = UUID.randomUUID();
        String key = "01JCONSENTIDEMPOTENT00000000";

        ConsentRecord first = service.update(
                userId,
                ConsentType.MICROPHONE,
                ConsentDecision.GRANTED,
                "privacy-v1",
                NOW,
                key);
        ConsentRecord second = service.update(
                userId,
                ConsentType.MICROPHONE,
                ConsentDecision.GRANTED,
                "privacy-v1",
                NOW,
                key);

        assertEquals(first.id(), second.id());
        assertEquals(1, records.records.size());
        BusinessException conflict = assertThrows(
                BusinessException.class,
                () -> service.update(
                        userId,
                        ConsentType.MICROPHONE,
                        ConsentDecision.REVOKED,
                        "privacy-v1",
                        NOW,
                        key));
        assertEquals(ErrorCode.SESSION_CONFLICT, conflict.errorCode());
    }

    private ConsentService service(InMemoryConsentRecordPort records, CountingOutboxPort outbox) {
        return new ConsentService(
                records,
                outbox,
                (userId, type, revokedAt) -> { },
                (actorUserId, action, result, reasonCode, occurredAt) -> { },
                new DigestService(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class CountingOutboxPort implements ConsentOutboxPort {

        private int count;

        @Override
        public void appendRevocation(UUID consentRecordId, UUID userId, ConsentType type, Instant createdAt) {
            count++;
        }
    }

    private static final class InMemoryConsentRecordPort implements ConsentRecordPort {

        private final List<ConsentRecord> records = new ArrayList<>();
        private final Map<String, ConsentRecord> byIdempotency = new HashMap<>();

        @Override
        public List<ConsentRecord> listByUser(UUID userId) {
            List<ConsentRecord> filtered = new ArrayList<>(records.stream()
                    .filter(record -> record.userId().equals(userId))
                    .toList());
            Collections.reverse(filtered);
            return filtered;
        }

        @Override
        public Optional<ConsentRecord> findByIdempotencyKey(
                UUID userId,
                ConsentType type,
                byte[] idempotencyKeyHash) {
            return Optional.ofNullable(byIdempotency.get(key(userId, type, idempotencyKeyHash)));
        }

        @Override
        public Optional<ConsentRecord> findLatest(UUID userId, ConsentType type) {
            for (int index = records.size() - 1; index >= 0; index--) {
                ConsentRecord record = records.get(index);
                if (record.userId().equals(userId) && record.type() == type) {
                    return Optional.of(record);
                }
            }
            return Optional.empty();
        }

        @Override
        public boolean append(ConsentRecord record) {
            String key = key(record.userId(), record.type(), record.idempotencyKeyHash());
            if (byIdempotency.containsKey(key)) {
                return false;
            }
            byIdempotency.put(key, record);
            records.add(record);
            return true;
        }

        private String key(UUID userId, ConsentType type, byte[] hash) {
            return userId + "|" + type + "|" + Base64.getEncoder().encodeToString(hash);
        }
    }
}
