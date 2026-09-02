package com.aifriend.voice.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.voice.domain.AudioObject;
import com.aifriend.voice.domain.AudioObjectStatus;
import com.aifriend.voice.domain.AudioPurpose;

class AudioObjectContentValidationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T01:00:00Z");
    private static final byte[] WAV_HEADER = "RIFF0000WAVEpayload"
            .getBytes(StandardCharsets.US_ASCII);
    private static final String STORAGE_VERSION = "sha256:version-one";

    private AudioObjectContentInspectorPort inspectorPort;
    private DigestService digestService;
    private AudioObjectContentValidationService service;

    @BeforeEach
    void setUp() {
        inspectorPort = mock(AudioObjectContentInspectorPort.class);
        digestService = new DigestService();
        service = new AudioObjectContentValidationService(digestService, inspectorPort);
    }

    @Test
    void shouldValidateExactBytesMagicAndDecodedDuration() {
        AudioObject audioObject = uploadedObject(WAV_HEADER, 2_000);
        when(inspectorPort.inspect("audio/wav", WAV_HEADER))
                .thenReturn(new AudioObjectInspection(2_100));

        ValidatedAudioObject validated = service.validate(
                audioObject,
                new StoredAudioObject(STORAGE_VERSION, WAV_HEADER));

        assertEquals(2_100, validated.actualDurationMs());
        assertEquals(audioObject.id(), validated.audioObjectId());
        assertEquals(audioObject.version(), validated.metadataVersion());
    }

    @Test
    void shouldAcceptStrongDiscoveredVersionBeforeDatabaseRegistration() {
        AudioObject audioObject = unrecordedObject(WAV_HEADER, 2_000);
        when(inspectorPort.inspect("audio/wav", WAV_HEADER))
                .thenReturn(new AudioObjectInspection(2_100));

        ValidatedAudioObject validated = service.validate(
                audioObject,
                new StoredAudioObject(STORAGE_VERSION, WAV_HEADER));

        assertEquals(STORAGE_VERSION, validated.storageVersion());
        assertEquals(2_100, validated.actualDurationMs());
    }

    @Test
    void shouldRejectBlankDiscoveredStorageVersion() {
        AudioObject audioObject = unrecordedObject(WAV_HEADER, 2_000);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.validate(
                        audioObject,
                        new StoredAudioObject(" ", WAV_HEADER)));

        assertEquals(ErrorCode.AUDIO_INVALID, exception.errorCode());
    }

    @Test
    void shouldRejectTamperingBeforeDecoderRuns() {
        AudioObject audioObject = uploadedObject(WAV_HEADER, 2_000);
        byte[] tampered = "RIFF0000WAVEchanged"
                .getBytes(StandardCharsets.US_ASCII);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.validate(
                        audioObject,
                        new StoredAudioObject(STORAGE_VERSION, tampered)));

        assertEquals(ErrorCode.AUDIO_INVALID, exception.errorCode());
    }

    @Test
    void shouldRejectDeclaredDurationOutsideDecodedTolerance() {
        AudioObject audioObject = uploadedObject(WAV_HEADER, 2_000);
        when(inspectorPort.inspect("audio/wav", WAV_HEADER))
                .thenReturn(new AudioObjectInspection(2_500));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.validate(
                        audioObject,
                        new StoredAudioObject(STORAGE_VERSION, WAV_HEADER)));

        assertEquals(ErrorCode.AUDIO_INVALID, exception.errorCode());
    }

    private AudioObject uploadedObject(byte[] content, int durationMs) {
        UUID id = UUID.randomUUID();
        return new AudioObject(
                id,
                UUID.randomUUID(),
                AudioPurpose.TASK,
                "audio/wav",
                content.length,
                durationMs,
                digestService.sha256(content),
                "temporary/" + id.toString().replace("-", ""),
                new byte[32],
                new byte[32],
                new byte[32],
                AudioObjectStatus.ISSUED,
                NOW.minusSeconds(30),
                NOW.plusSeconds(3_600),
                STORAGE_VERSION,
                NOW.minusSeconds(60),
                null,
                null,
                4L,
                NOW.minusSeconds(120),
                NOW.minusSeconds(60));
    }

    private AudioObject unrecordedObject(byte[] content, int durationMs) {
        UUID id = UUID.randomUUID();
        return new AudioObject(
                id,
                UUID.randomUUID(),
                AudioPurpose.TASK,
                "audio/wav",
                content.length,
                durationMs,
                digestService.sha256(content),
                "temporary/" + id.toString().replace("-", ""),
                new byte[32],
                new byte[32],
                new byte[32],
                AudioObjectStatus.ISSUED,
                NOW.plusSeconds(60),
                NOW.plusSeconds(3_600),
                null,
                null,
                null,
                null,
                4L,
                NOW.minusSeconds(120),
                NOW.minusSeconds(120));
    }
}
