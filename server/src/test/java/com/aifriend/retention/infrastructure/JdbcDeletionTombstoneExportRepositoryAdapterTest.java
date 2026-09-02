package com.aifriend.retention.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.aifriend.retention.application.DeletionTombstoneExportCandidate;
import com.aifriend.retention.application.EncryptedDeletionTombstoneEnvelope;
import com.aifriend.shared.security.DigestService;

class JdbcDeletionTombstoneExportRepositoryAdapterTest {
    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

    @Test
    void shouldReadBoundedReadyCandidateWithoutHoldingExternalTransaction() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet resultSet = resultSet();
        doAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbcTemplate).query(
                anyString(), any(RowMapper.class), any(Object[].class));
        JdbcDeletionTombstoneExportRepositoryAdapter adapter =
                new JdbcDeletionTombstoneExportRepositoryAdapter(jdbcTemplate);

        List<DeletionTombstoneExportCandidate> candidates = adapter.listReady(NOW, 10);

        assertEquals(1, candidates.size());
        assertEquals(2L, candidates.get(0).record().oldAccountGeneration());
        assertNull(candidates.get(0).preparedEnvelope());
        assertEquals(3, candidates.get(0).retryCount());
        assertInvocationContains(jdbcTemplate, "disaster_recovery_exported_at IS NULL");
        assertInvocationContains(jdbcTemplate, "export_next_attempt_at<=?");
    }

    @Test
    void shouldFixEnvelopeOnceAndClearCipherOnlyAfterConditionalReceiptConfirmation() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcDeletionTombstoneExportRepositoryAdapter adapter =
                new JdbcDeletionTombstoneExportRepositoryAdapter(jdbcTemplate);
        UUID tombstoneId = UUID.randomUUID();
        byte[] cipher = new byte[80];
        byte[] envelopeHash = new DigestService().sha256(cipher);
        byte[] receiptHash = new byte[32];
        EncryptedDeletionTombstoneEnvelope envelope =
                new EncryptedDeletionTombstoneEnvelope("dr-key-v1", cipher, envelopeHash);

        assertTrue(adapter.prepare(tombstoneId, envelope, NOW));
        assertTrue(adapter.markExported(tombstoneId, envelopeHash, NOW, receiptHash));

        assertInvocationContains(jdbcTemplate, "export_payload_hash IS NULL");
        assertInvocationContains(jdbcTemplate, "export_payload_cipher=NULL");
        assertInvocationContains(jdbcTemplate, "export_payload_hash=?");
        assertArrayInvocationPresent(jdbcTemplate, envelopeHash);
        assertArrayInvocationPresent(jdbcTemplate, receiptHash);
    }

    @Test
    void shouldRejectInvalidBatchAndNonIncreasingRetryTimeBeforeDatabaseAccess() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcDeletionTombstoneExportRepositoryAdapter adapter =
                new JdbcDeletionTombstoneExportRepositoryAdapter(jdbcTemplate);

        assertThrows(IllegalArgumentException.class, () -> adapter.listReady(NOW, 101));
        assertThrows(
                IllegalArgumentException.class,
                () -> adapter.markRetry(UUID.randomUUID(), new byte[32], NOW, NOW));

        verifyNoInteractions(jdbcTemplate);
    }

    private ResultSet resultSet() throws Exception {
        Instant acceptedAt = NOW.minusSeconds(80 * 60 * 60);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("tombstone_id")).thenReturn(UUID.randomUUID().toString());
        when(resultSet.getBytes("subject_hash")).thenReturn(new byte[32]);
        when(resultSet.getLong("old_account_generation")).thenReturn(2L);
        when(resultSet.getTimestamp("accepted_at")).thenReturn(Timestamp.from(acceptedAt));
        when(resultSet.getTimestamp("completed_at"))
                .thenReturn(Timestamp.from(acceptedAt.plusSeconds(60)));
        when(resultSet.getTimestamp("re_registration_not_before"))
                .thenReturn(Timestamp.from(acceptedAt.plusSeconds(72 * 60 * 60)));
        when(resultSet.getString("policy_version")).thenReturn("deletion-tombstone-v1");
        when(resultSet.getTimestamp("replay_until"))
                .thenReturn(Timestamp.from(NOW.plusSeconds(37 * 24 * 60 * 60)));
        when(resultSet.getString("export_key_id")).thenReturn(null);
        when(resultSet.getBytes("export_payload_cipher")).thenReturn(null);
        when(resultSet.getBytes("export_payload_hash")).thenReturn(null);
        when(resultSet.getInt("export_retry_count")).thenReturn(3);
        return resultSet;
    }

    private void assertInvocationContains(JdbcTemplate jdbcTemplate, String expected) {
        boolean found = mockingDetails(jdbcTemplate).getInvocations().stream()
                .flatMap(invocation -> java.util.Arrays.stream(invocation.getArguments()))
                .map(argument -> argument == null ? "null" : argument.toString())
                .anyMatch(argument -> argument.contains(expected));
        assertTrue(found);
    }

    private void assertArrayInvocationPresent(JdbcTemplate jdbcTemplate, byte[] expected) {
        boolean found = mockingDetails(jdbcTemplate).getInvocations().stream()
                .flatMap(invocation -> java.util.Arrays.stream(invocation.getArguments()))
                .filter(byte[].class::isInstance)
                .map(byte[].class::cast)
                .anyMatch(actual -> java.util.Arrays.equals(expected, actual));
        assertTrue(found);
    }
}
