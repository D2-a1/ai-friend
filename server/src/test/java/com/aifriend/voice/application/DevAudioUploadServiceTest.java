package com.aifriend.voice.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.voice.domain.AudioObject;
import com.aifriend.voice.domain.AudioObjectStatus;
import com.aifriend.voice.domain.AudioPurpose;

class DevAudioUploadServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-10T01:30:00Z");
    private static final String UPLOAD_TOKEN = "upload-secret-value";
    private static final byte[] CONTENT = new byte[] {1, 2, 3, 4};

    private AudioObjectRepositoryPort repository;
    private AudioObjectStoragePort storagePort;
    private DigestService digestService;
    private DevAudioUploadService service;

    @BeforeEach
    void setUp() {
        repository = mock(AudioObjectRepositoryPort.class);
        storagePort = mock(AudioObjectStoragePort.class);
        digestService = new DigestService();
        service = new DevAudioUploadService(
                repository,
                storagePort,
                digestService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldPersistOnlyMetadataAfterExactUploadMatch() {
        AudioObject issued = issuedObject(digestService.sha256(CONTENT));
        when(repository.findByIdForUpdate(issued.id())).thenReturn(Optional.of(issued));
        when(storagePort.store(issued.objectKey(), CONTENT)).thenReturn("storage-v1");
        when(repository.save(any(AudioObject.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.upload(
                PublicIdCodec.audioObjectId(issued.id()),
                UPLOAD_TOKEN,
                "audio/mp4; charset=binary",
                CONTENT);

        ArgumentCaptor<AudioObject> captor = ArgumentCaptor.forClass(AudioObject.class);
        verify(repository).save(captor.capture());
        assertEquals("storage-v1", captor.getValue().storageVersion());
        assertEquals(NOW, captor.getValue().uploadedAt());
        assertEquals(AudioObjectStatus.ISSUED, captor.getValue().status());
    }

    @Test
    void shouldRejectHashMismatchWithoutWritingObject() {
        AudioObject issued = issuedObject(new byte[32]);
        when(repository.findByIdForUpdate(issued.id())).thenReturn(Optional.of(issued));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.upload(
                        PublicIdCodec.audioObjectId(issued.id()),
                        UPLOAD_TOKEN,
                        "audio/mp4",
                        CONTENT));

        assertEquals(ErrorCode.AUDIO_INVALID, exception.errorCode());
        verify(storagePort, never()).store(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void shouldUseUnifiedFailureForUnknownObjectOrWrongToken() {
        UUID unknownId = UUID.randomUUID();
        when(repository.findByIdForUpdate(unknownId)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.upload(
                        PublicIdCodec.audioObjectId(unknownId),
                        "wrong-token",
                        "audio/mp4",
                        CONTENT));

        assertEquals(ErrorCode.AUDIO_INVALID, exception.errorCode());
        verify(storagePort, never()).store(any(), any());
    }

    private AudioObject issuedObject(byte[] contentHash) {
        UUID id = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        return new AudioObject(
                id,
                ownerUserId,
                AudioPurpose.TASK,
                "audio/mp4",
                CONTENT.length,
                2_000,
                contentHash,
                "temporary/" + id.toString().replace("-", ""),
                digestService.sha256(UPLOAD_TOKEN),
                new byte[32],
                new byte[32],
                AudioObjectStatus.ISSUED,
                NOW.plus(Duration.ofMinutes(5)),
                NOW.plus(Duration.ofHours(24)),
                null,
                null,
                null,
                null,
                0L,
                NOW.minusSeconds(60),
                NOW.minusSeconds(60));
    }
}
