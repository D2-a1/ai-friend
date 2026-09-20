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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.voice.application.AudioObjectStoragePort;

class JdbcAccountClosureCleanupAdapterTest {
    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

    @Test
    void shouldDeleteObjectBeforeOpeningShortDatabaseTransaction() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AudioObjectStoragePort storagePort = mock(AudioObjectStoragePort.class);
        SensitiveDataProtector protector = mock(SensitiveDataProtector.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        byte[] cipher = new byte[]{1, 2, 3};
        stubAudioQuery(jdbcTemplate, cipher);
        when(protector.decrypt(cipher)).thenReturn("owner/object.wav");
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        JdbcAccountClosureCleanupAdapter adapter = adapter(
                jdbcTemplate, storagePort, protector, transactionManager);

        int changed = adapter.cleanupBatch(UUID.randomUUID(), 50);

        assertEquals(1, changed);
        InOrder order = inOrder(storagePort, transactionManager);
        order.verify(storagePort).delete("owner/object.wav");
        order.verify(transactionManager).getTransaction(any());
        verify(transactionManager).commit(any());
    }

    @Test
    void shouldNotOpenDatabaseTransactionWhenObjectDeletionFails() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AudioObjectStoragePort storagePort = mock(AudioObjectStoragePort.class);
        SensitiveDataProtector protector = mock(SensitiveDataProtector.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        byte[] cipher = new byte[]{4, 5, 6};
        stubAudioQuery(jdbcTemplate, cipher);
        when(protector.decrypt(cipher)).thenReturn("owner/object.wav");
        org.mockito.Mockito.doThrow(new IllegalStateException("unavailable"))
                .when(storagePort).delete("owner/object.wav");
        JdbcAccountClosureCleanupAdapter adapter = adapter(
                jdbcTemplate, storagePort, protector, transactionManager);

        assertThrows(
                IllegalStateException.class,
                () -> adapter.cleanupBatch(UUID.randomUUID(), 50));

        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    void shouldWriteTombstoneBeforeReleasingSubjectAndCompletingClosure() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AudioObjectStoragePort storagePort = mock(AudioObjectStoragePort.class);
        SensitiveDataProtector protector = mock(SensitiveDataProtector.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        stubFinalizeRows(jdbcTemplate);
        when(jdbcTemplate.queryForObject(
                anyString(), org.mockito.ArgumentMatchers.eq(Long.class), any(Object[].class)))
                .thenReturn(0L);
        List<String> updates = new ArrayList<>();
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            updates.add(invocation.getArgument(0));
            return 1;
        });
        JdbcAccountClosureCleanupAdapter adapter = adapter(
                jdbcTemplate, storagePort, protector, transactionManager);

        boolean completed = adapter.finalizeIfCleared(
                UUID.randomUUID(), UUID.randomUUID(), NOW);

        assertTrue(completed);
        int tombstone = indexOfSql(updates, "INSERT INTO deletion_tombstone");
        int account = indexOfSql(updates, "UPDATE app_user SET");
        int closure = indexOfSql(updates, "UPDATE account_closure_request SET");
        assertTrue(tombstone >= 0 && tombstone < account);
        assertTrue(updates.get(tombstone).contains("export_next_attempt_at"));
        assertTrue(account < closure);
        assertTrue(updates.get(account).contains("wechat_open_id_hash=NULL"));
        verify(transactionManager).commit(any());
    }

    @Test
    void shouldUseSchemaOwnerColumnWhenDeletingConsentAggregate() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AudioObjectStoragePort storagePort = mock(AudioObjectStoragePort.class);
        SensitiveDataProtector protector = mock(SensitiveDataProtector.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbcTemplate.query(
                anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        List<String> updates = new ArrayList<>();
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            updates.add(invocation.getArgument(0));
            return 0;
        });
        JdbcAccountClosureCleanupAdapter adapter = adapter(
                jdbcTemplate, storagePort, protector, transactionManager);

        assertEquals(0, adapter.cleanupBatch(UUID.randomUUID(), 50));

        assertTrue(updates.stream().anyMatch(sql -> sql.contains(
                "FROM consent_record WHERE user_id=UUID_TO_BIN(?)")));
        assertTrue(updates.stream().anyMatch(sql -> sql.contains(
                "DELETE FROM consent_record WHERE user_id=UUID_TO_BIN(?)")));
        assertFalse(updates.stream().anyMatch(sql -> sql.contains(
                "consent_record WHERE owner_user_id=UUID_TO_BIN(?)")));
        assertTrue(updates.stream().anyMatch(sql -> sql.contains(
                "FROM contact_alias WHERE owner_user_id=UUID_TO_BIN(?)")));
        assertTrue(updates.stream().anyMatch(sql -> sql.contains(
                "DELETE FROM voice_training_dataset WHERE owner_user_id=UUID_TO_BIN(?)")));
        assertTrue(updates.stream().anyMatch(sql -> sql.contains(
                "DELETE FROM personal_assistant_memory WHERE owner_user_id=UUID_TO_BIN(?)")));
        verify(transactionManager).commit(any());
    }

    @Test
    void shouldDeleteAssistantAndGraphOneBoundedStageAtATimeBeforeOtherBusinessRows() {
        for (String stage : List.of("assistant_turn_request", "assistant_session", "knowledge_graph_edge", "knowledge_graph_node", "knowledge_graph_snapshot")) {
            var jdbc = mock(JdbcTemplate.class);
            var transactions = mock(PlatformTransactionManager.class);
            when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            UUID owner = UUID.randomUUID();
            var updates = new ArrayList<String>();
            when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(call -> {
                String sql = call.getArgument(0); updates.add(sql);
                assertEquals(owner.toString(), call.getArgument(1));
                assertTrue(sql.endsWith("LIMIT 50"));
                return sql.contains("FROM " + stage + " ") ? 50 : 0;
            });
            var cleanup = adapter(jdbc, mock(AudioObjectStoragePort.class), mock(SensitiveDataProtector.class), transactions);
            assertEquals(50, cleanup.cleanupBatch(owner, 50));
            assertEquals("DELETE FROM " + stage + " WHERE owner_user_id=UUID_TO_BIN(?) AND 1=1 LIMIT 50",
                    updates.get(updates.size() - 1));
            assertFalse(updates.stream().anyMatch(sql -> sql.contains("task_session")));
        }
    }

    @Test
    void shouldNotCompleteClosureWhileAnyAssistantOrGraphTableStillContainsRows() throws Exception {
        for (String table : List.of("assistant_turn_request", "assistant_session", "knowledge_graph_edge", "knowledge_graph_node", "knowledge_graph_snapshot")) {
            var jdbc = mock(JdbcTemplate.class);
            var transactions = mock(PlatformTransactionManager.class);
            when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            stubFinalizeRows(jdbc);
            when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class), any(Object[].class)))
                    .thenAnswer(call -> ((String) call.getArgument(0)).contains("FROM " + table + " ") ? 1L : 0L);
            var cleanup = adapter(jdbc, mock(AudioObjectStoragePort.class), mock(SensitiveDataProtector.class), transactions);
            assertFalse(cleanup.finalizeIfCleared(UUID.randomUUID(), UUID.randomUUID(), NOW));
            verify(jdbc, never()).update(anyString(), any(Object[].class));
        }
    }

    private JdbcAccountClosureCleanupAdapter adapter(
            JdbcTemplate jdbcTemplate,
            AudioObjectStoragePort storagePort,
            SensitiveDataProtector protector,
            PlatformTransactionManager transactionManager) {
        return new JdbcAccountClosureCleanupAdapter(
                jdbcTemplate,
                storagePort,
                protector,
                transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubAudioQuery(JdbcTemplate jdbcTemplate, byte[] cipher) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(1)).thenReturn(UUID.randomUUID().toString());
        when(resultSet.getBytes(2)).thenReturn(cipher);
        doAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbcTemplate).query(
                anyString(), any(RowMapper.class), any(Object[].class));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubFinalizeRows(JdbcTemplate jdbcTemplate) throws Exception {
        ResultSet closure = mock(ResultSet.class);
        when(closure.getLong("account_generation")).thenReturn(2L);
        when(closure.getTimestamp("accepted_at"))
                .thenReturn(Timestamp.from(NOW.minusSeconds(73 * 60 * 60)));
        when(closure.getTimestamp("re_registration_not_before"))
                .thenReturn(Timestamp.from(NOW.minusSeconds(60 * 60)));
        when(closure.getString("status")).thenReturn("ACCEPTED");
        ResultSet account = mock(ResultSet.class);
        when(account.getBytes("wechat_open_id_hash")).thenReturn(new byte[32]);
        when(account.getLong("account_generation")).thenReturn(2L);
        when(account.getString("status")).thenReturn("DELETING");
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowMapper mapper = invocation.getArgument(1);
            return mapper.mapRow(sql.contains("account_closure_request") ? closure : account, 0);
        }).when(jdbcTemplate).queryForObject(
                anyString(), any(RowMapper.class), any(Object[].class));
    }

    private int indexOfSql(List<String> updates, String prefix) {
        for (int index = 0; index < updates.size(); index++) {
            if (updates.get(index).startsWith(prefix)) {
                return index;
            }
        }
        return -1;
    }
}
