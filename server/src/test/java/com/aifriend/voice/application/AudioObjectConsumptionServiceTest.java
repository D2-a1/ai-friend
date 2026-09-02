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
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.voice.domain.AudioObject;
import com.aifriend.voice.domain.AudioObjectStatus;
import com.aifriend.voice.domain.AudioPurpose;

class AudioObjectConsumptionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T02:00:00Z");
    private static final byte[] CONTENT = new byte[] {1, 2, 3};

    private AudioObjectRepositoryPort repositoryPort;
    private AudioObjectStoragePort storagePort;
    private AudioObjectContentValidationService validationService;
    private AudioObjectConsumptionTransactionService transactionService;
    private AudioObjectConsumptionService service;

    @BeforeEach
    void setUp() {
        repositoryPort = mock(AudioObjectRepositoryPort.class);
        storagePort = mock(AudioObjectStoragePort.class);
        validationService = mock(AudioObjectContentValidationService.class);
        transactionService = mock(AudioObjectConsumptionTransactionService.class);
        service = new AudioObjectConsumptionService(
                repositoryPort,
                storagePort,
                validationService,
                transactionService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldReadValidateAndCommitUsingOwnerScopedSnapshot() {
        AudioObject uploaded = uploadedObject(AudioPurpose.TASK, NOW.plusSeconds(60));
        String publicId = PublicIdCodec.audioObjectId(uploaded.id());
        StoredAudioObject stored = new StoredAudioObject(uploaded.storageVersion(), CONTENT);
        ValidatedAudioObject validated = validated(uploaded);
        when(repositoryPort.findByIdAndOwner(uploaded.id(), uploaded.ownerUserId()))
                .thenReturn(Optional.of(uploaded));
        when(storagePort.readExact(
                uploaded.objectKey(), uploaded.storageVersion(), uploaded.expectedSizeBytes()))
                .thenReturn(stored);
        when(validationService.validate(uploaded, stored)).thenReturn(validated);
        when(transactionService.consumeAll(any(), any())).thenReturn("created");

        String result = service.consume(
                uploaded.ownerUserId(),
                publicId,
                AudioPurpose.TASK,
                ignored -> "created");

        assertEquals("created", result);
        verify(transactionService).consumeAll(any(), any());
    }

    @Test
    void shouldDiscoverCurrentObjectWhileUploadTicketIsValid() {
        AudioObject unrecorded = unrecordedObject(NOW.plusSeconds(60));
        String publicId = PublicIdCodec.audioObjectId(unrecorded.id());
        String discoveredVersion = "sha256:discovered-version";
        StoredAudioObject stored = new StoredAudioObject(discoveredVersion, CONTENT);
        ValidatedAudioObject validated = validated(unrecorded, discoveredVersion);
        when(repositoryPort.findByIdAndOwner(
                unrecorded.id(), unrecorded.ownerUserId()))
                .thenReturn(Optional.of(unrecorded));
        when(storagePort.readCurrent(
                unrecorded.objectKey(), unrecorded.expectedSizeBytes()))
                .thenReturn(stored);
        when(validationService.validate(unrecorded, stored)).thenReturn(validated);
        when(transactionService.consumeAll(any(), any())).thenReturn("created");

        String result = service.consume(
                unrecorded.ownerUserId(),
                publicId,
                AudioPurpose.TASK,
                ignored -> "created");

        assertEquals("created", result);
        verify(storagePort).readCurrent(
                unrecorded.objectKey(), unrecorded.expectedSizeBytes());
        verify(storagePort, never()).readExact(any(), any(), any(Long.class));
        verify(transactionService).consumeAll(any(), any());
    }

    @Test
    void shouldRejectUnrecordedObjectAfterUploadTicketExpires() {
        AudioObject unrecorded = unrecordedObject(NOW);
        when(repositoryPort.findByIdAndOwner(
                unrecorded.id(), unrecorded.ownerUserId()))
                .thenReturn(Optional.of(unrecorded));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.consume(
                        unrecorded.ownerUserId(),
                        PublicIdCodec.audioObjectId(unrecorded.id()),
                        AudioPurpose.TASK,
                        ignored -> null));

        assertEquals(ErrorCode.AUDIO_INVALID, exception.errorCode());
        verify(storagePort, never()).readCurrent(any(), any(Long.class));
        verify(storagePort, never()).readExact(any(), any(), any(Long.class));
    }

    @Test
    void shouldUseUnifiedFailureForWrongOwnerOrPurpose() {
        AudioObject uploaded = uploadedObject(AudioPurpose.TASK, NOW.plusSeconds(60));
        String publicId = PublicIdCodec.audioObjectId(uploaded.id());
        UUID wrongOwner = UUID.randomUUID();
        when(repositoryPort.findByIdAndOwner(uploaded.id(), wrongOwner))
                .thenReturn(Optional.empty());
        when(repositoryPort.findByIdAndOwner(uploaded.id(), uploaded.ownerUserId()))
                .thenReturn(Optional.of(uploaded));

        BusinessException wrongOwnerFailure = assertThrows(
                BusinessException.class,
                () -> service.consume(
                        wrongOwner, publicId, AudioPurpose.TASK, ignored -> null));
        BusinessException wrongPurposeFailure = assertThrows(
                BusinessException.class,
                () -> service.consume(
                        uploaded.ownerUserId(),
                        publicId,
                        AudioPurpose.ALIAS_ENROLLMENT,
                        ignored -> null));

        assertEquals(ErrorCode.AUDIO_INVALID, wrongOwnerFailure.errorCode());
        assertEquals(ErrorCode.AUDIO_INVALID, wrongPurposeFailure.errorCode());
        verify(storagePort, never()).readCurrent(any(), any(Long.class));
        verify(storagePort, never()).readExact(any(), any(), any(Long.class));
    }

    @Test
    void shouldRejectExpiredRetentionBeforeReadingStorage() {
        AudioObject expired = uploadedObject(AudioPurpose.TASK, NOW);
        when(repositoryPort.findByIdAndOwner(expired.id(), expired.ownerUserId()))
                .thenReturn(Optional.of(expired));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.consume(
                        expired.ownerUserId(),
                        PublicIdCodec.audioObjectId(expired.id()),
                        AudioPurpose.TASK,
                        ignored -> null));

        assertEquals(ErrorCode.AUDIO_INVALID, exception.errorCode());
        verify(storagePort, never()).readCurrent(any(), any(Long.class));
        verify(storagePort, never()).readExact(any(), any(), any(Long.class));
    }

    private AudioObject uploadedObject(AudioPurpose purpose, Instant retentionUntil) {
        UUID id = UUID.randomUUID();
        return new AudioObject(
                id,
                UUID.randomUUID(),
                purpose,
                "audio/wav",
                CONTENT.length,
                1_000,
                new byte[32],
                "temporary/" + id.toString().replace("-", ""),
                new byte[32],
                new byte[32],
                new byte[32],
                AudioObjectStatus.ISSUED,
                NOW.minusSeconds(1),
                retentionUntil,
                "sha256:version-one",
                NOW.minusSeconds(30),
                null,
                null,
                2L,
                NOW.minusSeconds(60),
                NOW.minusSeconds(30));
    }

    private AudioObject unrecordedObject(Instant uploadExpiresAt) {
        UUID id = UUID.randomUUID();
        return new AudioObject(
                id,
                UUID.randomUUID(),
                AudioPurpose.TASK,
                "audio/wav",
                CONTENT.length,
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
                2L,
                NOW.minusSeconds(60),
                NOW.minusSeconds(60));
    }

    private ValidatedAudioObject validated(AudioObject audioObject) {
        return validated(audioObject, audioObject.storageVersion());
    }

    private ValidatedAudioObject validated(
            AudioObject audioObject,
            String storageVersion) {
        return new ValidatedAudioObject(
                audioObject.id(),
                audioObject.ownerUserId(),
                audioObject.purpose(),
                audioObject.mediaType(),
                CONTENT,
                1_000,
                storageVersion,
                audioObject.version());
    }
}
