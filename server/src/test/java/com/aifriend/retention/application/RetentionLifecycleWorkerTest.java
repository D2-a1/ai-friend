package com.aifriend.retention.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.aifriend.shared.config.AiFriendProperties;

class RetentionLifecycleWorkerTest {
    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

    @Test
    void shouldUseConfiguredMessageCutoffAndBoundedBatch() {
        RetentionLifecyclePort lifecyclePort = Mockito.mock(RetentionLifecyclePort.class);
        AiFriendProperties properties = new AiFriendProperties(
                new AiFriendProperties.Api("1.0.0"),
                new AiFriendProperties.Retention(
                        Duration.ofHours(24), Duration.ofHours(12)));
        when(lifecyclePort.cleanupBatch(
                NOW, NOW.minus(Duration.ofHours(12)), 100)).thenReturn(7);
        RetentionLifecycleWorker worker = new RetentionLifecycleWorker(
                lifecyclePort, properties, Clock.fixed(NOW, ZoneOffset.UTC));

        int changed = worker.processReady();

        assertEquals(7, changed);
        verify(lifecyclePort).cleanupBatch(
                NOW, NOW.minus(Duration.ofHours(12)), 100);
    }
}
