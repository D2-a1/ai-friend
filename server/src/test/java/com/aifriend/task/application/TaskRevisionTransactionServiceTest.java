package com.aifriend.task.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aifriend.shared.security.DigestService;
import com.aifriend.task.domain.TaskAction;
import com.aifriend.task.domain.TaskIntent;
import com.aifriend.task.domain.TaskOperationType;
import com.aifriend.task.domain.TaskRevisionMode;
import com.aifriend.task.domain.TaskState;
import com.aifriend.voice.application.AudioObjectConsumer;
import com.aifriend.voice.application.AudioObjectConsumptionTransactionService;
import com.aifriend.voice.application.ValidatedAudioObject;
import com.aifriend.voice.domain.AudioPurpose;

class TaskRevisionTransactionServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-09-04T08:00:00Z");

    @Test
    void correctionMustInvalidateOldConfirmationAndPlanAtomically() {
        TaskSessionRepositoryPort repository =
                mock(TaskSessionRepositoryPort.class);
        AudioObjectConsumptionTransactionService audioTransaction =
                mock(AudioObjectConsumptionTransactionService.class);
        TaskPayloadCodec payloadCodec = mock(TaskPayloadCodec.class);
        TaskSessionMapper mapper = mock(TaskSessionMapper.class);
        TaskRevisionTransactionService service =
                new TaskRevisionTransactionService(
                        repository, audioTransaction, payloadCodec, mapper,
                        new DigestService(),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        UUID owner = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID oldAudioId = UUID.randomUUID();
        UUID newAudioId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        TaskClientContext clientContext = context();
        TaskMatchedContactView contact = new TaskMatchedContactView(
                "ct_demo1234", "老大", "老大");
        TaskUnderstandingView oldUnderstanding = understanding(
                TaskIntent.VOICE_CALL, contact, null);
        TaskPayload previous = new TaskPayload(
                clientContext, oldUnderstanding, List.of(),
                "给老大发起微信语音通话",
                Set.of(TaskAction.CONFIRM_CALL, TaskAction.CORRECT,
                        TaskAction.REJECT, TaskAction.CANCEL),
                NOW.minusSeconds(30), mock(WechatActionPlanView.class),
                null, mock(RoutineCommandLearningEvidence.class),
                TaskConversationContext.initial(
                        "给老大打电话", "给老大发起微信语音通话"));
        TaskStoredSession current = new TaskStoredSession(
                sessionId, owner, oldAudioId, "client-task",
                new byte[32], new byte[32], TaskState.AWAITING_CONFIRMATION,
                new byte[] {1}, contactId, "old-summary-hash",
                "old-plan", NOW.plusSeconds(30), 3,
                NOW.plusSeconds(60), NOW.minusSeconds(180), NOW.minusSeconds(10));
        ValidatedAudioObject newAudio = new ValidatedAudioObject(
                newAudioId, owner, AudioPurpose.TASK, "audio/wav",
                new byte[] {1, 2, 3}, 900, "storage-v2", 1);
        TaskUnderstandingView revisedUnderstanding = understanding(
                TaskIntent.VIDEO_CALL, contact, null);
        PreparedTaskRevision revision = new PreparedTaskRevision(
                current, newAudio, clientContext, revisedUnderstanding,
                List.of(), contact, contactId, false, false,
                TaskState.AWAITING_CONFIRMATION,
                "给老大发起微信视频通话", "new-summary-hash",
                TaskRevisionMode.CORRECTION, "不对，是视频通话");
        TaskSessionView expectedView = mock(TaskSessionView.class);

        when(repository.findByOwnerAndIdForUpdate(owner, sessionId))
                .thenReturn(Optional.of(current));
        when(repository.findOperation(
                eq(owner), eq(TaskOperationType.REVISION), any()))
                .thenReturn(Optional.empty());
        when(payloadCodec.decode(current.payloadCipher()))
                .thenReturn(previous);
        when(payloadCodec.encode(any())).thenReturn(new byte[] {2});
        when(repository.saveSession(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(mapper.toView(any())).thenReturn(expectedView);
        when(audioTransaction.consume(eq(newAudio), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    AudioObjectConsumer<TaskSessionView> consumer =
                            invocation.getArgument(1);
                    return consumer.consume(newAudio);
                });

        TaskSessionView actual = service.commit(
                owner, 3, new byte[] {3}, new byte[] {4}, revision);

        assertEquals(expectedView, actual);
        ArgumentCaptor<TaskStoredSession> sessionCaptor =
                ArgumentCaptor.forClass(TaskStoredSession.class);
        verify(repository).saveSession(sessionCaptor.capture());
        TaskStoredSession saved = sessionCaptor.getValue();
        assertEquals(4, saved.sessionVersion());
        assertEquals("new-summary-hash", saved.summaryHash());
        assertEquals(oldAudioId, saved.sourceAudioObjectId());
        assertNull(saved.planId());
        assertNull(saved.planExpiresAt());

        ArgumentCaptor<TaskPayload> payloadCaptor =
                ArgumentCaptor.forClass(TaskPayload.class);
        verify(payloadCodec).encode(payloadCaptor.capture());
        TaskPayload savedPayload = payloadCaptor.getValue();
        assertEquals(TaskIntent.VIDEO_CALL,
                savedPayload.understanding().intent());
        assertNull(savedPayload.actionPlan());
        assertNull(savedPayload.routineCommandLearningEvidence());
        assertEquals(NOW, savedPayload.confirmationStartedAt());
        assertTrue(savedPayload.allowedActions().contains(TaskAction.CORRECT));
        assertEquals(2, savedPayload.conversationContext().turnNumber());
        assertEquals("不对，是视频通话",
                savedPayload.conversationContext().turns().get(2).text());
        assertEquals("给老大发起微信视频通话",
                savedPayload.conversationContext().turns().get(3).text());
        verify(repository).saveOperation(any(TaskStoredOperation.class));
    }

    private TaskClientContext context() {
        return new TaskClientContext(
                "app-v1", "wechat-v1", "rule-v1", "wugang",
                "dialect-v1", "assist-v1", "fusion-v1",
                "template-v1", "threshold-v1");
    }

    private TaskUnderstandingView understanding(
            TaskIntent intent,
            TaskMatchedContactView contact,
            String messageText) {
        return new TaskUnderstandingView(
                intent, contact, "transcript", messageText,
                List.of(), List.of(), 0.9D,
                new TaskProcessingVersionsView(
                        "wugang", "dialect-v1", "asr-v1", "assist-v1",
                        "fusion-v1", "align-v1",
                        "template-v1", "threshold-v1"));
    }
}