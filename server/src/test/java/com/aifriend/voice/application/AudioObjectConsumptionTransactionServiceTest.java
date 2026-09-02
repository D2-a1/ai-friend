package com.aifriend.voice.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.voice.domain.AudioObject;
import com.aifriend.voice.domain.AudioObjectStatus;
import com.aifriend.voice.domain.AudioPurpose;

class AudioObjectConsumptionTransactionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T03:00:00Z");

    private AudioObjectRepositoryPort repositoryPort;
    private AudioObjectConsumptionTransactionService service;

    @BeforeEach
    void setUp() {
        repositoryPort = mock(AudioObjectRepositoryPort.class);
        service = new AudioObjectConsumptionTransactionService(
                repositoryPort,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldCommitConsumerAndConsumedStateTogether() {
        AudioObject issued = audioObject(AudioObjectStatus.ISSUED, null, 7L);
        ValidatedAudioObject validated = validated(issued, 7L);
        when(repositoryPort.findByIdAndOwnerForUpdate(issued.id(), issued.ownerUserId()))
                .thenReturn(Optional.of(issued));
        when(repositoryPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        String result = service.consume(validated, ignored -> "alias-created");

        assertEquals("alias-created", result);
        ArgumentCaptor<AudioObject> captor = ArgumentCaptor.forClass(AudioObject.class);
        verify(repositoryPort).save(captor.capture());
        assertEquals(AudioObjectStatus.CONSUMED, captor.getValue().status());
        assertEquals(NOW, captor.getValue().consumedAt());
    }

    @Test
    void shouldRegisterDiscoveredVersionAndConsumeInSameSave() {
        AudioObject unrecorded = unrecordedObject(NOW.plusSeconds(60));
        String discoveredVersion = "sha256:discovered-version";
        ValidatedAudioObject validated = validated(
                unrecorded, discoveredVersion, unrecorded.version());
        when(repositoryPort.findByIdAndOwnerForUpdate(
                unrecorded.id(), unrecorded.ownerUserId()))
                .thenReturn(Optional.of(unrecorded));
        when(repositoryPort.save(any())).thenAnswer(
                invocation -> invocation.getArgument(0));

        String result = service.consume(validated, ignored -> "task-created");

        assertEquals("task-created", result);
        ArgumentCaptor<AudioObject> captor = ArgumentCaptor.forClass(AudioObject.class);
        verify(repositoryPort).save(captor.capture());
        AudioObject saved = captor.getValue();
        assertEquals(AudioObjectStatus.CONSUMED, saved.status());
        assertEquals(discoveredVersion, saved.storageVersion());
        assertEquals(NOW, saved.uploadedAt());
        assertEquals(NOW, saved.consumedAt());
    }

    @Test
    void shouldRejectDiscoveryAfterUploadTicketExpires() {
        AudioObject unrecorded = unrecordedObject(NOW);
        ValidatedAudioObject validated = validated(
                unrecorded,
                "sha256:discovered-version",
                unrecorded.version());
        AtomicBoolean callbackCalled = new AtomicBoolean();
        when(repositoryPort.findByIdAndOwnerForUpdate(
                unrecorded.id(), unrecorded.ownerUserId()))
                .thenReturn(Optional.of(unrecorded));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.consume(validated, ignored -> {
                    callbackCalled.set(true);
                    return null;
                }));

        assertEquals(ErrorCode.AUDIO_INVALID, exception.errorCode());
        assertEquals(false, callbackCalled.get());
        verify(repositoryPort, never()).save(any());
    }

    @Test
    void shouldRejectSnapshotAfterConcurrentOrRepeatedConsumption() {
        AudioObject consumed = audioObject(AudioObjectStatus.CONSUMED, NOW.minusSeconds(1), 8L);
        ValidatedAudioObject stale = validated(consumed, 7L);
        AtomicBoolean callbackCalled = new AtomicBoolean();
        when(repositoryPort.findByIdAndOwnerForUpdate(consumed.id(), consumed.ownerUserId()))
                .thenReturn(Optional.of(consumed));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.consume(stale, ignored -> {
                    callbackCalled.set(true);
                    return null;
                }));

        assertEquals(ErrorCode.SESSION_CONFLICT, exception.errorCode());
        assertEquals(false, callbackCalled.get());
        verify(repositoryPort, never()).save(any());
    }

    @Test
    void shouldLeaveIssuedWhenBusinessConsumerFails() {
        AudioObject issued = audioObject(AudioObjectStatus.ISSUED, null, 7L);
        ValidatedAudioObject validated = validated(issued, 7L);
        when(repositoryPort.findByIdAndOwnerForUpdate(issued.id(), issued.ownerUserId()))
                .thenReturn(Optional.of(issued));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.consume(validated, ignored -> {
                    throw new IllegalStateException("business write failed");
                }));

        assertEquals("business write failed", exception.getMessage());
        verify(repositoryPort, never()).save(any());
    }

    @Test
    void shouldConsumeTwoValidatedObjectsAfterBothLocksSucceed() {
        UUID ownerUserId = UUID.randomUUID();
        AudioObject first = audioObject(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                ownerUserId, AudioObjectStatus.ISSUED, null, 1L);
        AudioObject second = audioObject(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                ownerUserId, AudioObjectStatus.ISSUED, null, 2L);
        when(repositoryPort.findByIdAndOwnerForUpdate(second.id(), ownerUserId))
                .thenReturn(Optional.of(second));
        when(repositoryPort.findByIdAndOwnerForUpdate(first.id(), ownerUserId))
                .thenReturn(Optional.of(first));
        when(repositoryPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        String result = service.consumeAll(
                List.of(validated(first, 1L), validated(second, 2L)),
                values -> "alias-created-atomically");

        assertEquals("alias-created-atomically", result);
        ArgumentCaptor<AudioObject> captor = ArgumentCaptor.forClass(AudioObject.class);
        verify(repositoryPort, org.mockito.Mockito.times(2)).save(captor.capture());
        assertEquals(
                List.of(AudioObjectStatus.CONSUMED, AudioObjectStatus.CONSUMED),
                captor.getAllValues().stream().map(AudioObject::status).toList());
    }

    @Test
    void shouldNotConsumeFirstWhenSecondSnapshotChanged() {
        UUID ownerUserId = UUID.randomUUID();
        AudioObject first = audioObject(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                ownerUserId, AudioObjectStatus.ISSUED, null, 1L);
        AudioObject second = audioObject(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                ownerUserId, AudioObjectStatus.CONSUMED, NOW.minusSeconds(1), 3L);
        when(repositoryPort.findByIdAndOwnerForUpdate(first.id(), ownerUserId))
                .thenReturn(Optional.of(first));
        when(repositoryPort.findByIdAndOwnerForUpdate(second.id(), ownerUserId))
                .thenReturn(Optional.of(second));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.consumeAll(
                        List.of(validated(first, 1L), validated(second, 2L)),
                        values -> "must-not-run"));

        assertEquals(ErrorCode.SESSION_CONFLICT, exception.errorCode());
        verify(repositoryPort, never()).save(any());
    }

    private AudioObject audioObject(
            AudioObjectStatus status,
            Instant consumedAt,
            long version) {
        return audioObject(UUID.randomUUID(), UUID.randomUUID(), status, consumedAt, version);
    }

    private AudioObject audioObject(
            UUID id,
            UUID ownerUserId,
            AudioObjectStatus status,
            Instant consumedAt,
            long version) {
        return new AudioObject(
                id,
                ownerUserId,
                AudioPurpose.ALIAS_ENROLLMENT,
                "audio/wav",
                3,
                1_000,
                new byte[32],
                "temporary/" + id.toString().replace("-", ""),
                new byte[32],
                new byte[32],
                new byte[32],
                status,
                NOW.minusSeconds(60),
                NOW.plusSeconds(3_600),
                "sha256:version-one",
                NOW.minusSeconds(30),
                consumedAt,
                null,
                version,
                NOW.minusSeconds(120),
                NOW.minusSeconds(30));
    }

    private AudioObject unrecordedObject(Instant uploadExpiresAt) {
        UUID id = UUID.randomUUID();
        return new AudioObject(
                id,
                UUID.randomUUID(),
                AudioPurpose.ALIAS_ENROLLMENT,
                "audio/wav",
                3,
                1_000,
                new byte[32],
                "temporary/" + id.toString().replace("-", ""),
                new byte[32],
                new byte[32],
                new byte[32],
                AudioObjectStatus.ISSUED,
                uploadExpiresAt,
                NOW.plusSeconds(3_600),
                null,
                null,
                null,
                null,
                7L,
                NOW.minusSeconds(120),
                NOW.minusSeconds(120));
    }

    private ValidatedAudioObject validated(AudioObject audioObject, long metadataVersion) {
        return validated(
                audioObject, audioObject.storageVersion(), metadataVersion);
    }

    private ValidatedAudioObject validated(
            AudioObject audioObject,
            String storageVersion,
            long metadataVersion) {
        return new ValidatedAudioObject(
                audioObject.id(),
                audioObject.ownerUserId(),
                audioObject.purpose(),
                audioObject.mediaType(),
                new byte[] {1, 2, 3},
                1_000,
                storageVersion,
                metadataVersion);
    }
}
