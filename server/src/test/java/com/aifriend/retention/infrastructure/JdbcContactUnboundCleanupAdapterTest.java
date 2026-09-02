package com.aifriend.retention.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.aifriend.retention.application.ContactUnboundCleanupJob;

class JdbcContactUnboundCleanupAdapterTest {

    private static final Instant NOW =
            Instant.parse("2026-08-25T10:00:00Z");

    @Test
    void shouldLockReadyEventAndDeriveOwnerFromRevokedBinding()
            throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UUID eventId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        List<String> queries = new ArrayList<>();
        stubQuery(
                jdbcTemplate,
                queries,
                eventId,
                contactId,
                ownerUserId);
        JdbcContactUnboundCleanupAdapter adapter =
                new JdbcContactUnboundCleanupAdapter(jdbcTemplate);

        ContactUnboundCleanupJob job = adapter.lockNextReady(NOW)
                .orElseThrow();

        assertEquals(eventId, job.eventId());
        assertEquals(contactId, job.contactId());
        assertEquals(ownerUserId, job.ownerUserId());
        assertTrue(queries.get(0).contains("JOIN contact_binding"));
        assertTrue(queries.get(0).contains("binding.status='REVOKED'"));
        assertTrue(queries.get(0).contains("FOR UPDATE SKIP LOCKED"));
        assertFalse(queries.get(0).contains("payload_json"));
    }

    @Test
    void shouldScrubAliasesAndClearNonTerminalTaskContext() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        List<String> updates = new ArrayList<>();
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    updates.add(sql);
                    return sql.contains("UPDATE task_session") ? 2 : 1;
                });
        JdbcContactUnboundCleanupAdapter adapter =
                new JdbcContactUnboundCleanupAdapter(jdbcTemplate);
        UUID ownerUserId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();

        assertEquals(1, adapter.scrubAliases(
                ownerUserId, contactId, NOW));
        assertEquals(2, adapter.cancelNonTerminalTasks(
                ownerUserId, contactId, NOW));

        assertEquals(3, updates.size());
        assertTrue(updates.get(0).contains("display_text_cipher=NULL"));
        assertTrue(updates.get(0).contains("template_cipher=NULL"));
        assertTrue(updates.get(0).contains("status='DELETED'"));
        assertTrue(updates.get(1).contains("selected_contact_id=NULL"));
        assertTrue(updates.get(1).contains("summary_hash=NULL"));
        assertTrue(updates.get(1).contains("plan_id=NULL"));
        assertTrue(updates.get(1).contains("'COMPLETED'"));
        assertTrue(updates.get(2).contains("DELETE candidate"));
    }

    @Test
    void shouldRequireExactlyOnePendingEventToComplete() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(0);
        JdbcContactUnboundCleanupAdapter adapter =
                new JdbcContactUnboundCleanupAdapter(jdbcTemplate);

        assertThrows(
                IllegalStateException.class,
                () -> adapter.markCompleted(UUID.randomUUID()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubQuery(
            JdbcTemplate jdbcTemplate,
            List<String> queries,
            UUID eventId,
            UUID contactId,
            UUID ownerUserId) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("event_id"))
                .thenReturn(eventId.toString());
        when(resultSet.getString("contact_id"))
                .thenReturn(contactId.toString());
        when(resultSet.getString("owner_user_id"))
                .thenReturn(ownerUserId.toString());
        doAnswer(invocation -> {
            queries.add(invocation.getArgument(0));
            RowMapper mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbcTemplate).query(
                anyString(), any(RowMapper.class), any(Object[].class));
    }
}
