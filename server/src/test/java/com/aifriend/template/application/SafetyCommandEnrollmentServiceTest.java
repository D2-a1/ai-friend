package com.aifriend.template.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.contact.application.AcousticEnrollmentCandidate;
import com.aifriend.contact.application.AcousticTemplatePort;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.template.domain.SafetyCommandTemplate;
import com.aifriend.template.domain.SafetyCommandTemplateStatus;
import com.aifriend.template.domain.SafetyCommandType;
import com.aifriend.voice.application.AudioObjectConsumptionService;
import com.aifriend.voice.application.ValidatedAudioObject;
import com.aifriend.voice.domain.AudioPurpose;

class SafetyCommandEnrollmentServiceTest {

    private static final String POLICY_VERSION = "voice-template-v1";
    private static final Instant NOW = Instant.parse("2026-08-13T03:00:00Z");

    private SafetyCommandTemplateRepositoryPort repositoryPort;
    private ConsentGrantQueryPort consentGrantQueryPort;
    private AudioObjectConsumptionService audioObjectConsumptionService;
    private AcousticTemplatePort acousticTemplatePort;
    private SafetyCommandEnrollmentTransactionService transactionService;
    private SafetyCommandEnrollmentService service;

    @BeforeEach
    void setUp() {
        repositoryPort = mock(SafetyCommandTemplateRepositoryPort.class);
        consentGrantQueryPort = mock(ConsentGrantQueryPort.class);
        audioObjectConsumptionService = mock(AudioObjectConsumptionService.class);
        acousticTemplatePort = mock(AcousticTemplatePort.class);
        transactionService = mock(SafetyCommandEnrollmentTransactionService.class);
        service = new SafetyCommandEnrollmentService(
                repositoryPort,
                consentGrantQueryPort,
                new DigestService(),
                audioObjectConsumptionService,
                acousticTemplatePort,
                transactionService,
                mock(SafetyCommandTemplateMapper.class));
    }

    @Test
    void shouldNormalizeFourCommandsAndCommitAllEightAudioObjects() {
        UUID ownerUserId = UUID.randomUUID();
        EnrollSafetyCommandsCommand command = commandInReverseOrder();
        List<ValidatedAudioObject> audioObjects = validatedAudioObjects(ownerUserId);
        List<AcousticEnrollmentCandidate> candidates = candidates();
        SafetyCommandEnrollmentResult expected =
                new SafetyCommandEnrollmentResult(true, List.of());
        when(repositoryPort.findEnrollment(eq(ownerUserId), any())).thenReturn(Optional.empty());
        when(consentGrantQueryPort.isGrantedForPolicy(
                ownerUserId, ConsentType.VOICE_TEMPLATE, POLICY_VERSION)).thenReturn(true);
        when(audioObjectConsumptionService.validateAll(
                eq(ownerUserId), anyList(), eq(AudioPurpose.SAFETY_COMMAND_ENROLLMENT)))
                .thenReturn(audioObjects);
        when(acousticTemplatePort.enroll(any(), any()))
                .thenReturn(candidates.get(0), candidates.get(1), candidates.get(2), candidates.get(3));
        when(acousticTemplatePort.isSafetyCommandDistinct(any(), anyList()))
                .thenReturn(true);
        when(transactionService.replace(
                eq(ownerUserId), any(), any(), any(), eq(audioObjects), eq(candidates)))
                .thenReturn(expected);

        SafetyCommandEnrollmentResult result = service.enroll(
                ownerUserId, "01JSAFETYENROLL0000000000001", command);

        assertSame(expected, result);
        ArgumentCaptor<EnrollSafetyCommandsCommand> commandCaptor =
                ArgumentCaptor.forClass(EnrollSafetyCommandsCommand.class);
        verify(transactionService).replace(
                eq(ownerUserId), any(), any(), commandCaptor.capture(),
                eq(audioObjects), eq(candidates));
        assertEquals(List.of(
                SafetyCommandType.CONFIRM_SEND,
                SafetyCommandType.CONFIRM_CALL,
                SafetyCommandType.CANCEL,
                SafetyCommandType.REJECT_RETRY),
                commandCaptor.getValue().commands().stream()
                        .map(SafetyCommandEnrollmentItem::type)
                        .toList());
    }

    @Test
    void shouldRejectDuplicateAudioBeforeConsentOrStorageRead() {
        UUID ownerUserId = UUID.randomUUID();
        List<SafetyCommandEnrollmentItem> items =
                new ArrayList<>(commandInReverseOrder().commands());
        SafetyCommandEnrollmentItem first = items.get(0);
        SafetyCommandEnrollmentItem second = items.get(1);
        items.set(1, new SafetyCommandEnrollmentItem(
                second.type(), first.firstAudioObjectId(), second.secondAudioObjectId()));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.enroll(
                        ownerUserId,
                        "01JSAFETYENROLL0000000000002",
                        new EnrollSafetyCommandsCommand(items, POLICY_VERSION)));

        assertEquals(ErrorCode.AUDIO_INVALID, exception.errorCode());
        verify(consentGrantQueryPort, never()).isGrantedForPolicy(any(), any(), any());
        verify(audioObjectConsumptionService, never()).validateAll(any(), anyList(), any());
    }

    @Test
    void shouldRequireTheExactVoiceTemplatePolicyBeforeAudioValidation() {
        UUID ownerUserId = UUID.randomUUID();
        when(repositoryPort.findEnrollment(eq(ownerUserId), any())).thenReturn(Optional.empty());
        when(consentGrantQueryPort.isGrantedForPolicy(
                ownerUserId, ConsentType.VOICE_TEMPLATE, POLICY_VERSION)).thenReturn(false);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.enroll(
                        ownerUserId,
                        "01JSAFETYENROLL0000000000003",
                        commandInReverseOrder()));

        assertEquals(ErrorCode.CONSENT_REQUIRED, exception.errorCode());
        verify(audioObjectConsumptionService, never()).validateAll(any(), anyList(), any());
        verify(transactionService, never()).replace(any(), any(), any(), any(), anyList(), anyList());
    }

    @Test
    void shouldNotRejectConsistentCommandsByCrossCategorySimilarity() {
        UUID ownerUserId = UUID.randomUUID();
        List<ValidatedAudioObject> audioObjects = validatedAudioObjects(ownerUserId);
        List<AcousticEnrollmentCandidate> candidates = candidates();
        when(repositoryPort.findEnrollment(eq(ownerUserId), any())).thenReturn(Optional.empty());
        when(consentGrantQueryPort.isGrantedForPolicy(
                ownerUserId, ConsentType.VOICE_TEMPLATE, POLICY_VERSION)).thenReturn(true);
        when(audioObjectConsumptionService.validateAll(
                eq(ownerUserId), anyList(), eq(AudioPurpose.SAFETY_COMMAND_ENROLLMENT)))
                .thenReturn(audioObjects);
        when(acousticTemplatePort.enroll(any(), any()))
                .thenReturn(candidates.get(0), candidates.get(1), candidates.get(2), candidates.get(3));
        service.enroll(
                ownerUserId,
                "01JSAFETYENROLL0000000000004",
                commandInReverseOrder());

        verify(acousticTemplatePort, never()).isSafetyCommandDistinct(any(), anyList());
        verify(transactionService).replace(
                eq(ownerUserId), any(), any(), any(), eq(audioObjects), eq(candidates));
    }

    @Test
    void shouldRejectAnOldReplayAfterACompleteReplacement() {
        UUID ownerUserId = UUID.randomUUID();
        EnrollSafetyCommandsCommand normalized = normalizedCommand();
        DigestService digestService = new DigestService();
        UUID oldEnrollmentId = UUID.randomUUID();
        SafetyCommandEnrollment replay = new SafetyCommandEnrollment(
                oldEnrollmentId,
                ownerUserId,
                digestService.sha256("01JSAFETYENROLL0000000000005"),
                digestService.sha256(normalized.fingerprintInput()),
                POLICY_VERSION,
                NOW);
        UUID currentEnrollmentId = UUID.randomUUID();
        when(repositoryPort.findEnrollment(eq(ownerUserId), any()))
                .thenReturn(Optional.of(replay));
        when(repositoryPort.findActiveByOwner(ownerUserId))
                .thenReturn(activeTemplates(ownerUserId, currentEnrollmentId));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.enroll(
                        ownerUserId,
                        "01JSAFETYENROLL0000000000005",
                        reverseCommand(normalized)));

        assertEquals(ErrorCode.SESSION_CONFLICT, exception.errorCode());
        verify(audioObjectConsumptionService, never()).validateAll(any(), anyList(), any());
        verify(transactionService, never()).replace(any(), any(), any(), any(), anyList(), anyList());
    }

    private EnrollSafetyCommandsCommand commandInReverseOrder() {
        return reverseCommand(normalizedCommand());
    }

    private EnrollSafetyCommandsCommand reverseCommand(
            EnrollSafetyCommandsCommand normalizedCommand) {
        List<SafetyCommandEnrollmentItem> normalized = normalizedCommand.commands();
        return new EnrollSafetyCommandsCommand(
                List.of(normalized.get(3), normalized.get(2), normalized.get(1), normalized.get(0)),
                POLICY_VERSION);
    }

    private EnrollSafetyCommandsCommand normalizedCommand() {
        List<SafetyCommandEnrollmentItem> items = new ArrayList<>();
        for (SafetyCommandType type : SafetyCommandType.values()) {
            items.add(new SafetyCommandEnrollmentItem(
                    type,
                    PublicIdCodec.audioObjectId(UUID.randomUUID()),
                    PublicIdCodec.audioObjectId(UUID.randomUUID())));
        }
        return new EnrollSafetyCommandsCommand(items, POLICY_VERSION);
    }

    private List<ValidatedAudioObject> validatedAudioObjects(UUID ownerUserId) {
        List<ValidatedAudioObject> audioObjects = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            audioObjects.add(new ValidatedAudioObject(
                    UUID.randomUUID(),
                    ownerUserId,
                    AudioPurpose.SAFETY_COMMAND_ENROLLMENT,
                    "audio/wav",
                    new byte[] {(byte) index},
                    1_200,
                    "version-" + index,
                    0));
        }
        return List.copyOf(audioObjects);
    }

    private List<AcousticEnrollmentCandidate> candidates() {
        List<AcousticEnrollmentCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            candidates.add(new AcousticEnrollmentCandidate(
                    new byte[] {(byte) (index + 1)},
                    "zh-Hans-CN-x-wugang",
                    "wugang-test-v1",
                    "content-template-test-v1",
                    "registration-threshold-test-v1"));
        }
        return List.copyOf(candidates);
    }

    private List<SafetyCommandTemplate> activeTemplates(
            UUID ownerUserId,
            UUID enrollmentId) {
        List<SafetyCommandTemplate> templates = new ArrayList<>();
        for (SafetyCommandType type : SafetyCommandType.values()) {
            templates.add(new SafetyCommandTemplate(
                    UUID.randomUUID(), enrollmentId, ownerUserId, type,
                    "zh-Hans-CN-x-wugang", "wugang-test-v1",
                    "content-template-test-v1", "registration-threshold-test-v1",
                    new byte[] {1}, new byte[] {2},
                    SafetyCommandTemplateStatus.ACTIVE, 0, NOW, NOW, null));
        }
        return List.copyOf(templates);
    }
}
