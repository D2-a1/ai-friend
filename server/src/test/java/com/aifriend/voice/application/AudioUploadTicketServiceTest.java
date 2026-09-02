package com.aifriend.voice.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.shared.config.AiFriendProperties;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.voice.domain.AudioObject;
import com.aifriend.voice.domain.AudioPurpose;
import com.aifriend.voicecollection.application.VoiceCollectionProperties;

class AudioUploadTicketServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-10T01:00:00Z");
    private static final String IDEMPOTENCY_KEY = "01JAUDIOUPLOAD00000000000001";

    private AudioObjectRepositoryPort repository;
    private AudioUploadTokenPort tokenPort;
    private AudioUploadTargetPort targetPort;
    private ConsentGrantQueryPort consentPort;
    private AuditEventPort auditEventPort;
    private AudioUploadTicketService service;

    @BeforeEach
    void setUp() {
        repository = mock(AudioObjectRepositoryPort.class);
        tokenPort = mock(AudioUploadTokenPort.class);
        targetPort = mock(AudioUploadTargetPort.class);
        consentPort = mock(ConsentGrantQueryPort.class);
        auditEventPort = mock(AuditEventPort.class);
        when(repository.save(any(AudioObject.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(tokenPort.issue()).thenReturn("upload-secret-one", "upload-secret-two");
        when(targetPort.createTarget(any(AudioObject.class), anyString()))
                .thenAnswer(invocation -> new AudioUploadTarget(
                        URI.create("http://10.0.2.2/upload/" + invocation
                                .getArgument(0, AudioObject.class).id()),
                        "PUT",
                        Map.of("X-Audio-Upload-Token", invocation.getArgument(1))));
        service = new AudioUploadTicketService(
                repository,
                tokenPort,
                targetPort,
                consentPort,
                new DigestService(),
                auditEventPort,
                new AudioStorageProperties(
                        Duration.ofMinutes(10),
                        URI.create("http://10.0.2.2:8080/api/v1/dev/audio-objects/"),
                        "target/dev-audio-objects",
                        Duration.ofMinutes(5),
                        100),
                new AiFriendProperties(
                        new AiFriendProperties.Api("1.0.0"),
                        new AiFriendProperties.Retention(
                                Duration.ofHours(24), Duration.ofHours(24))),
                new VoiceCollectionProperties(
                        "test-voice-collection-v1", "voice-model-training-v1",
                        "voice-sample-review-v1",
                        Duration.ofDays(30)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldCreateOwnerScopedTaskTicketWithFixedExpiry() {
        UUID ownerUserId = UUID.randomUUID();
        when(consentPort.isGranted(ownerUserId, ConsentType.TASK_AUDIO)).thenReturn(true);
        when(repository.findByIdempotencyKeyForUpdate(any(), any()))
                .thenReturn(Optional.empty());

        CreatedAudioUploadTicket result = service.create(
                ownerUserId, IDEMPOTENCY_KEY, validCommand());

        ArgumentCaptor<AudioObject> captor = ArgumentCaptor.forClass(AudioObject.class);
        verify(repository).lockOwner(ownerUserId);
        verify(repository).save(captor.capture());
        AudioObject persisted = captor.getValue();
        assertEquals(ownerUserId, persisted.ownerUserId());
        assertEquals(AudioPurpose.TASK, persisted.purpose());
        assertEquals(NOW.plus(Duration.ofMinutes(10)), result.expiresAt());
        assertEquals(NOW.plus(Duration.ofHours(24)), persisted.retentionUntil());
        assertEquals("PUT", result.uploadTarget().method());
        verify(auditEventPort).append(
                ownerUserId, "AUDIO_UPLOAD_TICKET_CREATE", "SUCCESS", "TASK", NOW);
    }

    @Test
    void shouldReplaySameObjectAndRotateUploadSecret() {
        UUID ownerUserId = UUID.randomUUID();
        when(consentPort.isGranted(ownerUserId, ConsentType.TASK_AUDIO)).thenReturn(true);
        when(repository.findByIdempotencyKeyForUpdate(any(), any()))
                .thenReturn(Optional.empty());
        CreatedAudioUploadTicket first = service.create(
                ownerUserId, IDEMPOTENCY_KEY, validCommand());
        ArgumentCaptor<AudioObject> firstSave = ArgumentCaptor.forClass(AudioObject.class);
        verify(repository).save(firstSave.capture());
        AudioObject existing = firstSave.getValue();
        when(repository.findByIdempotencyKeyForUpdate(any(), any()))
                .thenReturn(Optional.of(existing));

        CreatedAudioUploadTicket replay = service.create(
                ownerUserId, IDEMPOTENCY_KEY, validCommand());

        assertEquals(first.audioObjectId(), replay.audioObjectId());
        assertEquals(first.expiresAt(), replay.expiresAt());
        assertEquals("upload-secret-two",
                replay.uploadTarget().requiredHeaders().get("X-Audio-Upload-Token"));
    }

    @Test
    void shouldRequirePurposeSpecificConsentBeforePersistence() {
        UUID ownerUserId = UUID.randomUUID();
        when(consentPort.isGranted(ownerUserId, ConsentType.TASK_AUDIO)).thenReturn(false);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.create(ownerUserId, IDEMPOTENCY_KEY, validCommand()));

        assertEquals(ErrorCode.CONSENT_REQUIRED, exception.errorCode());
        verify(repository, never()).lockOwner(any());
        verify(targetPort, never()).createTarget(any(), anyString());
    }

    @Test
    void shouldRequireExactCollectionConsentAndUseThirtyDayRetention() {
        UUID ownerUserId = UUID.randomUUID();
        when(consentPort.isGrantedForPolicy(
                ownerUserId,
                ConsentType.TEST_VOICE_COLLECTION,
                "test-voice-collection-v1")).thenReturn(true);
        when(repository.findByIdempotencyKeyForUpdate(any(), any()))
                .thenReturn(Optional.empty());
        CreateAudioUploadTicketCommand command = new CreateAudioUploadTicketCommand(
                AudioPurpose.TEST_VOICE_COLLECTION,
                "audio/wav",
                32_044,
                1_000,
                "22".repeat(32));

        service.create(ownerUserId, IDEMPOTENCY_KEY, command);

        ArgumentCaptor<AudioObject> captor = ArgumentCaptor.forClass(AudioObject.class);
        verify(repository).save(captor.capture());
        assertEquals(NOW.plus(Duration.ofDays(30)), captor.getValue().retentionUntil());
        verify(consentPort).isGrantedForPolicy(
                ownerUserId,
                ConsentType.TEST_VOICE_COLLECTION,
                "test-voice-collection-v1");
        verify(consentPort, never()).isGranted(ownerUserId, ConsentType.VOICE_TEMPLATE);
    }

    @Test
    void shouldRejectInvalidBoundsEvenWhenCalledOutsideController() {
        UUID ownerUserId = UUID.randomUUID();
        CreateAudioUploadTicketCommand oversized = new CreateAudioUploadTicketCommand(
                AudioPurpose.TASK,
                "audio/mp4",
                20_971_521,
                1_000,
                "11".repeat(32));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.create(ownerUserId, IDEMPOTENCY_KEY, oversized));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.errorCode());
        verify(consentPort, never()).isGranted(any(), any());
    }

    private CreateAudioUploadTicketCommand validCommand() {
        return new CreateAudioUploadTicketCommand(
                AudioPurpose.TASK,
                "audio/mp4",
                1_024,
                2_000,
                "11".repeat(32));
    }
}
