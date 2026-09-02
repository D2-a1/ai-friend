package com.aifriend.task.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.task.domain.TaskIntent;
import com.aifriend.task.domain.TaskState;

class TaskContinuationResolverTest {

    private static final Instant NOW = Instant.parse("2026-08-22T08:00:00Z");

    private TaskSessionRepositoryPort repositoryPort;
    private TaskPayloadCodec payloadCodec;
    private TaskContinuationContactProjectionPort contactProjectionPort;
    private TaskContinuationResolver resolver;

    @BeforeEach
    void setUp() {
        repositoryPort = mock(TaskSessionRepositoryPort.class);
        payloadCodec = mock(TaskPayloadCodec.class);
        contactProjectionPort = mock(TaskContinuationContactProjectionPort.class);
        resolver = new TaskContinuationResolver(
                repositoryPort,
                payloadCodec,
                contactProjectionPort,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldReuseActiveContactAfterTrustedSentMessage() {
        UUID owner = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        TaskStoredSession latest = latestSession(contactId, NOW.minusSeconds(60));
        TaskPayload payload = payload(contactId, new TaskChannelResultView(
                "SENT",
                List.of(
                        new TaskChannelPartView("AUDIO", "SENT", "audio-proof"),
                        new TaskChannelPartView("TEXT", "SENT", "text-proof")),
                NOW.minusSeconds(60)));
        when(repositoryPort.findLatestByOwner(owner)).thenReturn(Optional.of(latest));
        when(payloadCodec.decode(any(byte[].class))).thenReturn(payload);
        when(contactProjectionPort.isActive(owner, contactId)).thenReturn(true);

        Optional<TaskContinuationResolution> resolution = resolver.resolve(
                owner, PublicIdCodec.contactId(contactId));

        assertTrue(resolution.isPresent());
        assertEquals(latest.id(), resolution.orElseThrow().sourceSessionId());
        assertEquals(PublicIdCodec.contactId(contactId),
                resolution.orElseThrow().candidate().publicContactId());
    }

    @Test
    void shouldRejectPartialWithoutAudioSentEvidence() {
        UUID owner = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        TaskStoredSession latest = latestSession(
                contactId, NOW.minusSeconds(10), TaskState.PARTIAL);
        TaskPayload payload = payload(contactId, new TaskChannelResultView(
                "PARTIAL",
                List.of(
                        new TaskChannelPartView("AUDIO", "HANDED_TO_WECHAT", null),
                        new TaskChannelPartView("TEXT", "FAILED", null)),
                NOW.minusSeconds(10)));
        when(repositoryPort.findLatestByOwner(owner)).thenReturn(Optional.of(latest));
        when(payloadCodec.decode(any(byte[].class))).thenReturn(payload);
        when(contactProjectionPort.isActive(owner, contactId)).thenReturn(true);

        Optional<TaskContinuationResolution> resolution = resolver.resolve(
                owner, PublicIdCodec.contactId(contactId));

        assertTrue(resolution.isEmpty());
    }

    @Test
    void shouldRejectExpiredOrInactiveContactContext() {
        UUID owner = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        TaskStoredSession latest = latestSession(contactId, NOW.minusSeconds(76));
        when(repositoryPort.findLatestByOwner(owner)).thenReturn(Optional.of(latest));

        assertTrue(resolver.resolve(owner, PublicIdCodec.contactId(contactId)).isEmpty());
    }

    private TaskStoredSession latestSession(UUID contactId, Instant updatedAt) {
        return latestSession(contactId, updatedAt, TaskState.COMPLETED);
    }

    private TaskStoredSession latestSession(
            UUID contactId,
            Instant updatedAt,
            TaskState state) {
        return new TaskStoredSession(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "client-task", new byte[32], new byte[32], state,
                new byte[] {1}, contactId, "summary", "plan",
                NOW.plusSeconds(30), 3, NOW.plusSeconds(300),
                NOW.minusSeconds(70), updatedAt);
    }

    private TaskPayload payload(
            UUID contactId,
            TaskChannelResultView channelResult) {
        TaskMatchedContactView contact = new TaskMatchedContactView(
                PublicIdCodec.contactId(contactId), "李明", "二狗子");
        TaskUnderstandingView understanding = mock(TaskUnderstandingView.class);
        when(understanding.intent()).thenReturn(TaskIntent.SEND_MESSAGE);
        when(understanding.contact()).thenReturn(contact);
        TaskPayload payload = mock(TaskPayload.class);
        when(payload.understanding()).thenReturn(understanding);
        when(payload.channelResult()).thenReturn(channelResult);
        return payload;
    }
}
