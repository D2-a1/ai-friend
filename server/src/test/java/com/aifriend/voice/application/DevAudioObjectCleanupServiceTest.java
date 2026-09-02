package com.aifriend.voice.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.voice.domain.AudioObject;
import com.aifriend.voice.domain.AudioObjectStatus;
import com.aifriend.voice.domain.AudioPurpose;

class DevAudioObjectCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-10T02:00:00Z");

    @Test
    void shouldNeverDeleteUploadedObjectWhenTicketExpires() {
        AudioObjectRepositoryPort repository = mock(AudioObjectRepositoryPort.class);
        AudioObjectStoragePort storage = mock(AudioObjectStoragePort.class);
        AudioObject uploaded = uploadedObject();
        when(repository.findExpiredIssued(NOW, 100)).thenReturn(List.of(uploaded));
        when(repository.findByIdForUpdate(uploaded.id())).thenReturn(Optional.of(uploaded));
        DevAudioObjectCleanupService service = new DevAudioObjectCleanupService(
                repository,
                storage,
                new AudioStorageProperties(
                        Duration.ofMinutes(10),
                        URI.create("http://10.0.2.2:8080/api/v1/dev/audio-objects/"),
                        "target/dev-audio-objects",
                        Duration.ofMinutes(5),
                        100),
                Clock.fixed(NOW, ZoneOffset.UTC));

        int deleted = service.cleanupExpired();

        assertEquals(0, deleted);
        verify(storage, never()).delete(any());
        verify(repository, never()).save(any());
    }

    private AudioObject uploadedObject() {
        UUID id = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        return new AudioObject(
                id,
                ownerUserId,
                AudioPurpose.ALIAS_ENROLLMENT,
                "audio/aac",
                4,
                1_000,
                new byte[32],
                "temporary/" + id.toString().replace("-", ""),
                new byte[32],
                new byte[32],
                new byte[32],
                AudioObjectStatus.ISSUED,
                NOW.minusSeconds(1),
                NOW.plus(Duration.ofHours(23)),
                "storage-v1",
                NOW.minusSeconds(10),
                null,
                null,
                1L,
                NOW.minus(Duration.ofMinutes(20)),
                NOW.minusSeconds(10));
    }
}
