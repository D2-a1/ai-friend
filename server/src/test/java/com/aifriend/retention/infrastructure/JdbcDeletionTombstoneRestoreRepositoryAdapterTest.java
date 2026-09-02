package com.aifriend.retention.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.aifriend.retention.application.DeletionTombstoneExportRecord;

class JdbcDeletionTombstoneRestoreRepositoryAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final UUID TOMBSTONE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void shouldInsertMissingTombstoneInShortTransactionWithExportRetryTime() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = transactionManager();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcDeletionTombstoneRestoreRepositoryAdapter adapter =
                new JdbcDeletionTombstoneRestoreRepositoryAdapter(
                        jdbcTemplate, transactionManager);

        adapter.replayBatch(List.of(record()), NOW);

        assertInvocationContains(jdbcTemplate, "INSERT INTO deletion_tombstone");
        assertInvocationContains(jdbcTemplate, "export_next_attempt_at");
        verify(transactionManager).commit(any());
    }

    @Test
    void shouldTreatExactExistingImmutableFactsAsIdempotentReplay() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = transactionManager();
        stubTombstoneQuery(jdbcTemplate, resultSet("deletion-tombstone-v1"));
        JdbcDeletionTombstoneRestoreRepositoryAdapter adapter =
                new JdbcDeletionTombstoneRestoreRepositoryAdapter(
                        jdbcTemplate, transactionManager);

        adapter.replayBatch(List.of(record()), NOW);

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(transactionManager).commit(any());
    }

    @Test
    void shouldRollbackWhenExistingImmutableFactConflicts() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = transactionManager();
        stubTombstoneQuery(jdbcTemplate, resultSet("conflicting-policy"));
        JdbcDeletionTombstoneRestoreRepositoryAdapter adapter =
                new JdbcDeletionTombstoneRestoreRepositoryAdapter(
                        jdbcTemplate, transactionManager);

        assertThrows(
                IllegalStateException.class,
                () -> adapter.replayBatch(List.of(record()), NOW));

        verify(transactionManager).rollback(any());
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void shouldCountOnlyActiveOrDeletingAccountsAtOldOrEqualGeneration() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class)))
                .thenReturn(2L);
        JdbcDeletionTombstoneRestoreRepositoryAdapter adapter =
                new JdbcDeletionTombstoneRestoreRepositoryAdapter(
                        jdbcTemplate, transactionManager());

        assertEquals(2L, adapter.countResurrectedAccounts());
        assertInvocationContains(jdbcTemplate, "account_generation<=");
        assertInvocationContains(jdbcTemplate, "status IN ('ACTIVE','DELETING')");
    }

    @Test
    void shouldInsertBoundedVerificationFactAfterSnapshotLock() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = transactionManager();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcDeletionTombstoneRestoreRepositoryAdapter adapter =
                new JdbcDeletionTombstoneRestoreRepositoryAdapter(
                        jdbcTemplate, transactionManager);

        adapter.recordVerification(
                new byte[32], new byte[32], 1L, 1L, new byte[32], NOW);

        assertInvocationContains(
                jdbcTemplate, "INSERT INTO disaster_recovery_restore_verification");
        assertInvocationContains(jdbcTemplate, "WHERE snapshot_id_hash=? FOR UPDATE");
    }

    @Test
    void shouldRejectDifferentManifestForAlreadyVerifiedSnapshot() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = transactionManager();
        ResultSet resultSet = mock(ResultSet.class);
        byte[] differentManifest = new byte[32];
        differentManifest[0] = 1;
        when(resultSet.getBytes("manifest_hash")).thenReturn(differentManifest);
        when(resultSet.getLong("expected_item_count")).thenReturn(1L);
        when(resultSet.getLong("replayed_item_count")).thenReturn(1L);
        when(resultSet.getBytes("source_proof_hash")).thenReturn(new byte[32]);
        doAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbcTemplate).query(
                anyString(), any(RowMapper.class), any(Object[].class));
        JdbcDeletionTombstoneRestoreRepositoryAdapter adapter =
                new JdbcDeletionTombstoneRestoreRepositoryAdapter(
                        jdbcTemplate, transactionManager);

        assertThrows(
                IllegalStateException.class,
                () -> adapter.recordVerification(
                        new byte[32], new byte[32], 1L, 1L, new byte[32], NOW));

        verify(transactionManager).rollback(any());
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return transactionManager;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubTombstoneQuery(JdbcTemplate jdbcTemplate, ResultSet resultSet)
            throws Exception {
        doAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbcTemplate).query(
                anyString(), any(RowMapper.class), any(Object[].class));
    }

    private ResultSet resultSet(String policyVersion) throws Exception {
        DeletionTombstoneExportRecord record = record();
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("tombstone_id")).thenReturn(TOMBSTONE_ID.toString());
        when(resultSet.getBytes("subject_hash")).thenReturn(record.subjectHash());
        when(resultSet.getLong("old_account_generation"))
                .thenReturn(record.oldAccountGeneration());
        when(resultSet.getTimestamp("accepted_at"))
                .thenReturn(Timestamp.from(record.acceptedAt()));
        when(resultSet.getTimestamp("completed_at"))
                .thenReturn(Timestamp.from(record.completedAt()));
        when(resultSet.getTimestamp("re_registration_not_before"))
                .thenReturn(Timestamp.from(record.reRegistrationNotBefore()));
        when(resultSet.getString("policy_version")).thenReturn(policyVersion);
        when(resultSet.getTimestamp("replay_until"))
                .thenReturn(Timestamp.from(record.replayUntil()));
        return resultSet;
    }

    private DeletionTombstoneExportRecord record() {
        byte[] subjectHash = new byte[32];
        subjectHash[0] = 1;
        return new DeletionTombstoneExportRecord(
                TOMBSTONE_ID,
                subjectHash,
                2L,
                NOW.minusSeconds(10_000),
                NOW.minusSeconds(9_000),
                NOW.minusSeconds(8_000),
                "deletion-tombstone-v1",
                NOW.plusSeconds(10_000));
    }

    private void assertInvocationContains(JdbcTemplate jdbcTemplate, String expected) {
        boolean found = mockingDetails(jdbcTemplate).getInvocations().stream()
                .flatMap(invocation -> java.util.Arrays.stream(invocation.getArguments()))
                .map(argument -> argument == null ? "null" : argument.toString())
                .anyMatch(argument -> argument.contains(expected));
        assertTrue(found);
    }
}
