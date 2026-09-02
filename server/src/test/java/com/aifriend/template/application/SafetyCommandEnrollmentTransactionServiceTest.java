package com.aifriend.template.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.contact.application.AcousticEnrollmentCandidate;
import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.template.domain.SafetyCommandType;
import com.aifriend.voice.application.AudioObjectBatchConsumer;
import com.aifriend.voice.application.AudioObjectConsumptionTransactionService;
import com.aifriend.voice.application.ValidatedAudioObject;
import com.aifriend.voice.domain.AudioPurpose;

class SafetyCommandEnrollmentTransactionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T04:00:00Z");
    private static final String POLICY_VERSION = "voice-template-v1";

    private UUID ownerUserId;
    private SafetyCommandTemplateRepositoryPort repositoryPort;
    private ConsentGrantQueryPort consentGrantQueryPort;
    private AudioObjectConsumptionTransactionService audioTransactionService;
    private SafetyCommandOutboxPort outboxPort;
    private AuditEventPort auditEventPort;
    private SafetyCommandEnrollmentTransactionService service;

    @BeforeEach
    void setUp() {
        ownerUserId = UUID.randomUUID();
        repositoryPort = mock(SafetyCommandTemplateRepositoryPort.class);
        consentGrantQueryPort = mock(ConsentGrantQueryPort.class);
        audioTransactionService = mock(AudioObjectConsumptionTransactionService.class);
        outboxPort = mock(SafetyCommandOutboxPort.class);
        auditEventPort = mock(AuditEventPort.class);
        SensitiveDataProtector protector = mock(SensitiveDataProtector.class);
        when(protector.encryptBytes(any())).thenReturn(new byte[] {9, 8, 7});
        SafetyCommandTemplateMapper mapper = mock(SafetyCommandTemplateMapper.class);
        when(mapper.toSummary(any())).thenReturn(mock(VoiceTemplateSummary.class));
        service = new SafetyCommandEnrollmentTransactionService(
                repositoryPort,
                consentGrantQueryPort,
                audioTransactionService,
                protector,
                new DigestService(),
                mapper,
                outboxPort,
                auditEventPort,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReplaceFourTemplatesAndConsumeEightAudioObjectsAtomically() {
        List<ValidatedAudioObject> audioObjects = audioObjects();
        EnrollSafetyCommandsCommand command = command();
        List<AcousticEnrollmentCandidate> candidates = candidates();
        when(repositoryPort.lockNamespace(ownerUserId, NOW)).thenReturn(6L);
        when(repositoryPort.findEnrollmentForUpdate(eq(ownerUserId), any()))
                .thenReturn(Optional.empty());
        when(consentGrantQueryPort.isGrantedForPolicy(
                ownerUserId, ConsentType.VOICE_TEMPLATE, POLICY_VERSION)).thenReturn(true);
        when(repositoryPort.replaceActiveAndFlush(ownerUserId, NOW)).thenReturn(4);
        when(repositoryPort.saveTemplate(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(audioTransactionService.consumeAll(eq(audioObjects), any())).thenAnswer(invocation -> {
            AudioObjectBatchConsumer<SafetyCommandEnrollmentResult> consumer =
                    invocation.getArgument(1);
            return consumer.consume(audioObjects);
        });

        SafetyCommandEnrollmentResult result = service.replace(
                ownerUserId,
                new DigestService().sha256("idempotency-key"),
                new DigestService().sha256(command.fingerprintInput()),
                command,
                audioObjects,
                candidates);

        assertEquals(true, result.complete());
        assertEquals(4, result.templates().size());
        verify(repositoryPort).replaceActiveAndFlush(ownerUserId, NOW);
        verify(repositoryPort).saveEnrollment(any());
        verify(repositoryPort, times(4)).saveTemplate(any());
        verify(repositoryPort).incrementNamespace(ownerUserId, 6L, NOW);
        verify(outboxPort).appendReplaced(any(), eq(ownerUserId), eq(NOW));
        verify(auditEventPort).append(
                ownerUserId, "SAFETY_COMMAND_ENROLL", "SUCCESS", null, NOW);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectAPartialExistingSetBeforeWritingTheNewBatch() {
        List<ValidatedAudioObject> audioObjects = audioObjects();
        EnrollSafetyCommandsCommand command = command();
        when(repositoryPort.lockNamespace(ownerUserId, NOW)).thenReturn(6L);
        when(repositoryPort.findEnrollmentForUpdate(eq(ownerUserId), any()))
                .thenReturn(Optional.empty());
        when(consentGrantQueryPort.isGrantedForPolicy(
                ownerUserId, ConsentType.VOICE_TEMPLATE, POLICY_VERSION)).thenReturn(true);
        when(repositoryPort.replaceActiveAndFlush(ownerUserId, NOW)).thenReturn(2);
        when(audioTransactionService.consumeAll(eq(audioObjects), any())).thenAnswer(invocation -> {
            AudioObjectBatchConsumer<SafetyCommandEnrollmentResult> consumer =
                    invocation.getArgument(1);
            return consumer.consume(audioObjects);
        });

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.replace(
                        ownerUserId,
                        new DigestService().sha256("idempotency-key"),
                        new DigestService().sha256(command.fingerprintInput()),
                        command,
                        audioObjects,
                        candidates()));

        assertEquals(ErrorCode.SESSION_CONFLICT, exception.errorCode());
        verify(repositoryPort, never()).saveEnrollment(any());
        verify(repositoryPort, never()).saveTemplate(any());
        verify(outboxPort, never()).appendReplaced(any(), any(), any());
        verify(auditEventPort, never()).append(any(), any(), any(), any(), any());
    }

    private EnrollSafetyCommandsCommand command() {
        List<SafetyCommandEnrollmentItem> items = new ArrayList<>();
        for (SafetyCommandType type : SafetyCommandType.values()) {
            items.add(new SafetyCommandEnrollmentItem(
                    type, "au-first-" + type.name(), "au-second-" + type.name()));
        }
        return new EnrollSafetyCommandsCommand(items, POLICY_VERSION);
    }

    private List<ValidatedAudioObject> audioObjects() {
        List<ValidatedAudioObject> values = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            values.add(new ValidatedAudioObject(
                    UUID.randomUUID(), ownerUserId,
                    AudioPurpose.SAFETY_COMMAND_ENROLLMENT,
                    "audio/wav", new byte[] {(byte) index}, 1_000,
                    "storage-version-" + index, 0));
        }
        return List.copyOf(values);
    }

    private List<AcousticEnrollmentCandidate> candidates() {
        List<AcousticEnrollmentCandidate> values = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            values.add(new AcousticEnrollmentCandidate(
                    new byte[] {(byte) index},
                    "zh-Hans-CN-x-wugang",
                    "wugang-test-v1",
                    "content-template-test-v1",
                    "registration-threshold-test-v1"));
        }
        return List.copyOf(values);
    }
}
