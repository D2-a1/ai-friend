package com.aifriend.retention.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.shared.logging.TestLogCapture;
import com.aifriend.voice.application.AudioObjectStoragePort;

class JdbcRetentionLifecycleAdapterTest {
    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");
    private static final String SENSITIVE_FAILURE_DETAIL = "storage-secret-detail";

    @Test
    void shouldDeleteAudioOutsideTransactionThenClearDatabaseInForeignKeyOrder()
            throws Exception {
        Fixture fixture = fixture();
        byte[] cipher = new byte[]{1, 2, 3};
        stubAudioQuery(fixture.jdbcTemplate(), cipher, 0);
        when(fixture.protector().decrypt(cipher)).thenReturn("owner/object.wav");
        when(fixture.jdbcTemplate().update(anyString(), any(Object[].class)))
                .thenReturn(1);

        int changed = fixture.adapter().cleanupBatch(
                NOW, NOW.minusSeconds(24 * 60 * 60), 100);

        assertEquals(6, changed);
        assertEquals(1.0, fixture.meterRegistry().counter(
                "ai.friend.retention.audio.deletions",
                "outcome", "confirmed").count());
        assertEquals(0.0, fixture.meterRegistry().counter(
                "ai.friend.retention.audio.deletions",
                "outcome", "retry").count());
        InOrder externalOrder = inOrder(fixture.storagePort(), fixture.transactionManager());
        externalOrder.verify(fixture.storagePort()).delete("owner/object.wav");
        externalOrder.verify(fixture.transactionManager()).getTransaction(any());
        assertSqlBefore(fixture.jdbcTemplate(),
                "DELETE FROM routine_command_learning_outbox",
                "DELETE FROM task_operation");
        assertSqlBefore(fixture.jdbcTemplate(),
                "DELETE FROM task_operation", "DELETE FROM task_session");
        assertSqlBefore(fixture.jdbcTemplate(),
                "DELETE FROM invitation_session", "DELETE FROM contact_invitation");
        assertSqlBefore(fixture.jdbcTemplate(),
                "DELETE FROM contact_invitation", "DELETE FROM deletion_tombstone");
        assertInvocationContains(fixture.jdbcTemplate(),
                "disaster_recovery_exported_at IS NOT NULL");
        assertInvocationContains(fixture.jdbcTemplate(),
                "export_receipt_hash IS NOT NULL");
    }

    @Test
    void shouldRecordBackoffWhenAudioDeletionFailsAndContinueDatabaseCleanup()
            throws Exception {
        Fixture fixture = fixture();
        byte[] cipher = new byte[]{4, 5, 6};
        stubAudioQuery(fixture.jdbcTemplate(), cipher, 3);
        when(fixture.protector().decrypt(cipher)).thenReturn("owner/object.wav");
        org.mockito.Mockito.doThrow(new IllegalStateException(SENSITIVE_FAILURE_DETAIL))
                .when(fixture.storagePort()).delete("owner/object.wav");
        when(fixture.jdbcTemplate().update(anyString(), any(Object[].class)))
                .thenAnswer(invocation -> invocation.getArgument(0).toString()
                        .contains("retention_retry_count=retention_retry_count+1")
                        ? 1 : 0);

        int changed;
        List<String> messages;
        try (TestLogCapture capture = TestLogCapture.forClass(
                JdbcRetentionLifecycleAdapter.class)) {
            changed = fixture.adapter().cleanupBatch(
                    NOW, NOW.minusSeconds(24 * 60 * 60), 100);
            messages = capture.messages();
        }

        assertEquals(0, changed);
        assertEquals(0.0, fixture.meterRegistry().counter(
                "ai.friend.retention.audio.deletions",
                "outcome", "confirmed").count());
        assertEquals(1.0, fixture.meterRegistry().counter(
                "ai.friend.retention.audio.deletions",
                "outcome", "retry").count());
        assertInvocationContains(fixture.jdbcTemplate(),
                "retention_retry_count=retention_retry_count+1");
        assertTimestampInvocation(
                fixture.jdbcTemplate(), NOW.plusSeconds(4 * 60));
        assertInvocationContains(fixture.jdbcTemplate(), "DELETE FROM task_operation");
        assertEquals(1, messages.size());
        String message = messages.get(0);
        assertTrue(message.contains("stage=RETENTION_AUDIO_DELETION"));
        assertTrue(message.contains("errorType=IllegalStateException"));
        assertTrue(message.contains("retryCount=3"));
        assertTrue(message.contains("nextAttemptAt=2026-08-20T10:04:00Z"));
        assertFalse(message.contains(SENSITIVE_FAILURE_DETAIL));
        assertFalse(message.contains("owner/object.wav"));
    }

    @Test
    void shouldSkipAudioWhoseRetryTimeIsNotReturnedByReadyQuery() throws Exception {
        Fixture fixture = fixture();
        stubEmptyAudioQuery(fixture.jdbcTemplate());
        when(fixture.jdbcTemplate().update(anyString(), any(Object[].class)))
                .thenReturn(0);

        int changed = fixture.adapter().cleanupBatch(
                NOW, NOW.minusSeconds(24 * 60 * 60), 100);

        assertEquals(0, changed);
        verify(fixture.storagePort(), org.mockito.Mockito.never()).delete(anyString());
    }

    @Test
    void shouldDeleteTombstoneOnlyThroughReplayDeadlineAndExportReceiptGate() {
        Fixture fixture = fixture();
        stubEmptyAudioQuery(fixture.jdbcTemplate());
        when(fixture.jdbcTemplate().update(anyString(), any(Object[].class)))
                .thenAnswer(invocation -> invocation.getArgument(0).toString()
                        .startsWith("DELETE FROM deletion_tombstone") ? 1 : 0);

        int changed = fixture.adapter().cleanupBatch(
                NOW, NOW.minusSeconds(24 * 60 * 60), 100);

        assertEquals(1, changed);
        assertInvocationContains(fixture.jdbcTemplate(), "replay_until<=?");
        assertInvocationContains(fixture.jdbcTemplate(),
                "disaster_recovery_exported_at IS NOT NULL");
        assertInvocationContains(fixture.jdbcTemplate(),
                "export_receipt_hash IS NOT NULL");
    }

    @Test
    void shouldRejectUnboundedBatchBeforeAccessingStorage() {
        Fixture fixture = fixture();

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.adapter().cleanupBatch(NOW, NOW, 501));

        verifyNoInteractions(fixture.storagePort());
        verifyNoInteractions(fixture.jdbcTemplate());
    }

    private Fixture fixture() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AudioObjectStoragePort storagePort = mock(AudioObjectStoragePort.class);
        SensitiveDataProtector protector = mock(SensitiveDataProtector.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        when(transactionManager.getTransaction(any())).thenAnswer(
                invocation -> new SimpleTransactionStatus());
        JdbcRetentionLifecycleAdapter adapter = new JdbcRetentionLifecycleAdapter(
                jdbcTemplate, storagePort, protector, transactionManager,
                meterRegistry);
        return new Fixture(
                adapter, jdbcTemplate, storagePort, protector,
                transactionManager, meterRegistry);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubAudioQuery(JdbcTemplate jdbcTemplate, byte[] cipher, int retryCount)
            throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(1)).thenReturn(UUID.randomUUID().toString());
        when(resultSet.getBytes(2)).thenReturn(cipher);
        when(resultSet.getInt(3)).thenReturn(retryCount);
        doAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbcTemplate).query(
                anyString(), any(RowMapper.class), any(Object[].class));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubEmptyAudioQuery(JdbcTemplate jdbcTemplate) {
        when(jdbcTemplate.query(
                anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
    }

    private void assertSqlBefore(JdbcTemplate jdbcTemplate, String first, String second) {
        List<String> sql = mockingDetails(jdbcTemplate).getInvocations().stream()
                .filter(invocation -> "update".equals(invocation.getMethod().getName()))
                .map(invocation -> (String) invocation.getArgument(0))
                .toList();
        int firstIndex = indexContaining(sql, first);
        int secondIndex = indexContaining(sql, second);
        org.junit.jupiter.api.Assertions.assertTrue(
                firstIndex >= 0 && secondIndex > firstIndex);
    }

    private void assertInvocationContains(JdbcTemplate jdbcTemplate, String expected) {
        boolean found = mockingDetails(jdbcTemplate).getInvocations().stream()
                .flatMap(invocation -> java.util.Arrays.stream(invocation.getArguments()))
                .map(argument -> argument == null ? "null" : argument.toString())
                .anyMatch(argument -> argument.contains(expected));
        org.junit.jupiter.api.Assertions.assertTrue(found);
    }

    private void assertTimestampInvocation(JdbcTemplate jdbcTemplate, Instant expected) {
        boolean found = mockingDetails(jdbcTemplate).getInvocations().stream()
                .flatMap(invocation -> java.util.Arrays.stream(invocation.getArguments()))
                .filter(Timestamp.class::isInstance)
                .map(Timestamp.class::cast)
                .anyMatch(timestamp -> expected.equals(timestamp.toInstant()));
        org.junit.jupiter.api.Assertions.assertTrue(found);
    }

    private int indexContaining(List<String> values, String expected) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).contains(expected)) {
                return index;
            }
        }
        return -1;
    }

    private record Fixture(
            JdbcRetentionLifecycleAdapter adapter,
            JdbcTemplate jdbcTemplate,
            AudioObjectStoragePort storagePort,
            SensitiveDataProtector protector,
            PlatformTransactionManager transactionManager,
            SimpleMeterRegistry meterRegistry) {
    }
}
