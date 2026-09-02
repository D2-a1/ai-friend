package com.aifriend.template.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aifriend.dialect.application.DialectPackageManifest;
import com.aifriend.dialect.application.DialectPackageRegistry;
import com.aifriend.dialect.application.VerifiedDialectPackage;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.task.application.RoutineCommandLearningEvidence;
import com.aifriend.task.application.TaskAudioRange;
import com.aifriend.task.application.TaskClientContext;
import com.aifriend.task.application.TaskRoutineCommandMatchDecision;
import com.aifriend.task.application.TaskRoutineCommandMatchStatus;
import com.aifriend.task.domain.TaskIntent;
import com.aifriend.voice.application.ValidatedAudioObject;
import com.aifriend.voice.domain.AudioPurpose;

class RoutineCommandTaskMatcherServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T03:00:00Z");

    private final RoutineCommandRuntimeSnapshotService snapshotService =
            mock(RoutineCommandRuntimeSnapshotService.class);
    private final RoutineCommandRuntimeAcousticPort acousticPort =
            mock(RoutineCommandRuntimeAcousticPort.class);
    private final DialectPackageRegistry packageRegistry =
            mock(DialectPackageRegistry.class);
    private final SensitiveDataProtector protector =
            mock(SensitiveDataProtector.class);
    private final DigestService digestService = new DigestService();
    private final UUID ownerUserId = UUID.randomUUID();
    private final byte[] plainTemplate = new byte[] {1, 2, 3, 4};

    private RoutineCommandTaskMatcherService service;

    @BeforeEach
    void setUp() {
        service = new RoutineCommandTaskMatcherService(
                snapshotService, acousticPort, packageRegistry,
                protector, digestService);
    }

    @Test
    void absentActionEvidenceMustNotReadOwnerTemplates() {
        TaskRoutineCommandMatchDecision decision = service.match(
                ownerUserId, audio(), context(), TaskIntent.SEND_MESSAGE, null);

        assertEquals(TaskRoutineCommandMatchStatus.NOT_APPLICABLE,
                decision.status());
        verifyNoInteractions(snapshotService, acousticPort, packageRegistry, protector);
    }

    @Test
    void uniqueSameIntentMustCorroborateAndClearPlainTemplate() {
        stubSnapshot(TaskIntent.VOICE_CALL, true);
        when(acousticPort.classify(any(), any(), anyList(), any()))
                .thenReturn(RoutineCommandRuntimeMatch.unique(TaskIntent.VOICE_CALL));
        ArgumentCaptor<List<RoutineCommandRuntimeTemplate>> templates =
                ArgumentCaptor.forClass(List.class);

        TaskRoutineCommandMatchDecision decision = service.match(
                ownerUserId, audio(), context(), TaskIntent.VOICE_CALL,
                evidence(TaskIntent.VOICE_CALL));

        assertEquals(TaskRoutineCommandMatchStatus.CORROBORATED,
                decision.status());
        assertEquals(TaskIntent.VOICE_CALL, decision.matchedIntent());
        verify(acousticPort).classify(any(), any(), templates.capture(), any());
        assertTrue(templates.getValue().get(0).material().length > 0);
        assertTrue(allZero(templates.getValue().get(0).material()));
    }

    @Test
    void uniqueDifferentIntentMustRequireRetry() {
        stubSnapshot(TaskIntent.VIDEO_CALL, true);
        when(acousticPort.classify(any(), any(), anyList(), any()))
                .thenReturn(RoutineCommandRuntimeMatch.unique(TaskIntent.VIDEO_CALL));

        TaskRoutineCommandMatchDecision decision = service.match(
                ownerUserId, audio(), context(), TaskIntent.VOICE_CALL,
                evidence(TaskIntent.VOICE_CALL));

        assertEquals(TaskRoutineCommandMatchStatus.CONFLICT, decision.status());
        assertTrue(decision.requiresRetry());
    }

    @Test
    void namespaceChangeDuringAcousticWorkMustFailClosed() {
        stubSnapshot(TaskIntent.VOICE_CALL, false);
        when(acousticPort.classify(any(), any(), anyList(), any()))
                .thenReturn(RoutineCommandRuntimeMatch.none());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.match(ownerUserId, audio(), context(),
                        TaskIntent.VOICE_CALL, evidence(TaskIntent.VOICE_CALL)));

        assertEquals(ErrorCode.SESSION_CONFLICT, exception.errorCode());
    }

    @Test
    void corruptTemplateDigestMustFailBeforeAcousticComparison() {
        RoutineCommandTemplateRecord record = record(
                TaskIntent.VOICE_CALL, new byte[32]);
        when(snapshotService.snapshot(ownerUserId)).thenReturn(Optional.of(
                new RoutineCommandRuntimeSnapshot(3L, List.of(record))));
        when(packageRegistry.findActive()).thenReturn(Optional.of(
                new VerifiedDialectPackage(manifest(), null)));
        when(protector.decryptBytes(any())).thenReturn(plainTemplate.clone());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.match(ownerUserId, audio(), context(),
                        TaskIntent.VOICE_CALL, evidence(TaskIntent.VOICE_CALL)));

        assertEquals(ErrorCode.TEMPLATE_INCOMPATIBLE, exception.errorCode());
        verify(acousticPort, never()).classify(any(), any(), anyList(), any());
    }

    private void stubSnapshot(TaskIntent templateIntent, boolean current) {
        RoutineCommandTemplateRecord record = record(
                templateIntent, digestService.sha256(plainTemplate));
        when(snapshotService.snapshot(ownerUserId)).thenReturn(Optional.of(
                new RoutineCommandRuntimeSnapshot(3L, List.of(record))));
        when(snapshotService.isCurrent(ownerUserId, 3L)).thenReturn(current);
        when(packageRegistry.findActive()).thenReturn(Optional.of(
                new VerifiedDialectPackage(manifest(), null)));
        when(protector.decryptBytes(any())).thenReturn(plainTemplate.clone());
    }

    private RoutineCommandTemplateRecord record(
            TaskIntent intent,
            byte[] digest) {
        return new RoutineCommandTemplateRecord(
                UUID.randomUUID(), intent, "zh-Hans-CN-x-wugang",
                "dialect-v1", "mfcc-v1", "threshold-v1",
                new byte[] {9, 8, 7}, digest, 1, NOW, 0L, NOW);
    }

    private RoutineCommandLearningEvidence evidence(TaskIntent intent) {
        return new RoutineCommandLearningEvidence(
                intent, new TaskAudioRange(100, 600));
    }

    private ValidatedAudioObject audio() {
        return new ValidatedAudioObject(
                UUID.randomUUID(), ownerUserId, AudioPurpose.TASK,
                "audio/wav", new byte[] {1}, 1_000, "v1", 0L);
    }

    private TaskClientContext context() {
        return new TaskClientContext(
                "1.0.0", "wechat-v1", "rule-v1",
                "zh-Hans-CN-x-wugang", "dialect-v1", "assist-v1",
                "fusion-v1", "mfcc-v1", "threshold-v1");
    }

    private DialectPackageManifest manifest() {
        return new DialectPackageManifest(
                "zh-Hans-CN-x-wugang", "dialect-v1", "MFCC_DTW_V1",
                "mfcc-v1", "threshold-v1", "primary-v1", "1".repeat(64),
                "assist-v1", "2".repeat(64), "fusion-v1", "alignment-v1",
                "1.0.0", "1.0.0", "calibration.json", "3".repeat(64),
                "key-v1", "2026-08-24T03:00:00Z");
    }

    private boolean allZero(byte[] value) {
        for (byte item : value) {
            if (item != 0) {
                return false;
            }
        }
        return true;
    }
}
