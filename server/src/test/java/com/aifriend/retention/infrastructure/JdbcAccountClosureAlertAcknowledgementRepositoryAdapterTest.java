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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.aifriend.retention.application.AccountClosureAlertAcknowledgement;
import com.aifriend.retention.application.AccountClosureAlertAcknowledgementTiming;
import com.aifriend.retention.application.AccountClosureAlertAcknowledgementWrite;
import com.aifriend.retention.application.AccountClosureAlertAudience;

class JdbcAccountClosureAlertAcknowledgementRepositoryAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-20T15:00:00Z");
    private static final Instant P0_OPENED_AT = NOW.minusSeconds(5 * 60);
    private static final Instant DUE_AT = NOW.plusSeconds(10 * 60);

    @Test
    void shouldAtomicallyRecordTimelyAcknowledgementWithoutEscalation() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubQueries(jdbcTemplate, targetRow("ON_CALL", "DELIVERED", DUE_AT, null, null), null);
        List<String> updates = captureSuccessfulUpdates(jdbcTemplate);
        JdbcAccountClosureAlertAcknowledgementRepositoryAdapter adapter = adapter(jdbcTemplate);

        AccountClosureAlertAcknowledgement result = adapter.acknowledge(
                write(AccountClosureAlertAudience.ON_CALL, NOW));

        assertEquals(AccountClosureAlertAcknowledgementTiming.TIMELY, result.timing());
        assertEquals(2, updates.size());
        assertTrue(updates.get(0).startsWith(
                "INSERT INTO account_closure_alert_acknowledgement"));
        assertTrue(updates.get(1).contains("SET acknowledged_at=?"));
        assertTrue(updates.stream().noneMatch(
                sql -> sql.contains("ACCOUNT_CLOSURE_P0_ESCALATED")));
        assertInvocationContains(jdbcTemplate, "FOR UPDATE");
    }

    @Test
    void shouldClassifyDeadlineEqualityAsLateAndAppendEscalation() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubQueries(jdbcTemplate, targetRow("ON_CALL", "DELIVERED", NOW, null, null), null);
        List<String> updates = captureSuccessfulUpdates(jdbcTemplate);
        JdbcAccountClosureAlertAcknowledgementRepositoryAdapter adapter = adapter(jdbcTemplate);

        AccountClosureAlertAcknowledgement result = adapter.acknowledge(
                write(AccountClosureAlertAudience.ON_CALL, NOW));

        assertEquals(AccountClosureAlertAcknowledgementTiming.LATE, result.timing());
        assertEquals(3, updates.size());
        assertTrue(updates.get(1).contains("escalated_at=?"));
        assertTrue(updates.get(2).contains("ACCOUNT_CLOSURE_P0_ESCALATED"));
    }

    @Test
    void shouldKeepExistingEscalationWithoutPublishingDuplicate() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubQueries(
                jdbcTemplate,
                targetRow(
                        "PRIVACY_OFFICER",
                        "DELIVERED",
                        NOW.minusSeconds(60),
                        null,
                        NOW.minusSeconds(30)),
                null);
        List<String> updates = captureSuccessfulUpdates(jdbcTemplate);
        JdbcAccountClosureAlertAcknowledgementRepositoryAdapter adapter = adapter(jdbcTemplate);

        AccountClosureAlertAcknowledgement result = adapter.acknowledge(
                write(AccountClosureAlertAudience.PRIVACY_OFFICER, NOW));

        assertEquals(AccountClosureAlertAcknowledgementTiming.LATE, result.timing());
        assertEquals(2, updates.size());
        assertTrue(updates.stream().noneMatch(
                sql -> sql.contains("ACCOUNT_CLOSURE_P0_ESCALATED")));
    }

    @Test
    void shouldReplayOnlySameIdempotencyAndRequestDigests() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AccountClosureAlertAcknowledgementWrite write =
                write(AccountClosureAlertAudience.ON_CALL, NOW);
        ResultSet existing = acknowledgementRow(
                write.idempotencyKeyHash(), write.requestHash());
        stubQueries(
                jdbcTemplate,
                targetRow("ON_CALL", "DELIVERED", DUE_AT, NOW, null),
                existing);
        JdbcAccountClosureAlertAcknowledgementRepositoryAdapter adapter = adapter(jdbcTemplate);

        AccountClosureAlertAcknowledgement replay = adapter.acknowledge(write);

        assertEquals(AccountClosureAlertAcknowledgementTiming.TIMELY, replay.timing());
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void shouldRejectConflictingReplayWithoutStateChange() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AccountClosureAlertAcknowledgementWrite write =
                write(AccountClosureAlertAudience.ON_CALL, NOW);
        byte[] differentRequestHash = write.requestHash();
        differentRequestHash[0] = 1;
        ResultSet existing = acknowledgementRow(
                write.idempotencyKeyHash(), differentRequestHash);
        stubQueries(
                jdbcTemplate,
                targetRow("ON_CALL", "DELIVERED", DUE_AT, NOW, null),
                existing);
        JdbcAccountClosureAlertAcknowledgementRepositoryAdapter adapter = adapter(jdbcTemplate);

        assertThrows(IllegalStateException.class, () -> adapter.acknowledge(write));

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void shouldRejectUndeliveredOrWrongAudienceBeforeWriting() throws Exception {
        JdbcTemplate pendingJdbc = mock(JdbcTemplate.class);
        stubQueries(
                pendingJdbc,
                targetRow("ON_CALL", "PENDING", DUE_AT, null, null),
                null);
        JdbcAccountClosureAlertAcknowledgementRepositoryAdapter pendingAdapter =
                adapter(pendingJdbc);

        assertThrows(
                IllegalStateException.class,
                () -> pendingAdapter.acknowledge(
                        write(AccountClosureAlertAudience.ON_CALL, NOW)));
        verify(pendingJdbc, never()).update(anyString(), any(Object[].class));

        JdbcTemplate wrongGroupJdbc = mock(JdbcTemplate.class);
        stubQueries(
                wrongGroupJdbc,
                targetRow("ON_CALL", "DELIVERED", DUE_AT, null, null),
                null);
        JdbcAccountClosureAlertAcknowledgementRepositoryAdapter wrongGroupAdapter =
                adapter(wrongGroupJdbc);
        assertThrows(
                IllegalStateException.class,
                () -> wrongGroupAdapter.acknowledge(
                        write(AccountClosureAlertAudience.PRIVACY_OFFICER, NOW)));
        verify(wrongGroupJdbc, never()).update(anyString(), any(Object[].class));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubQueries(
            JdbcTemplate jdbcTemplate,
            ResultSet target,
            ResultSet acknowledgement) throws Exception {
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowMapper mapper = invocation.getArgument(1);
            if (sql.contains("FROM account_closure_alert_delivery")) {
                return List.of(mapper.mapRow(target, 0));
            }
            if (acknowledgement == null) {
                return List.of();
            }
            return List.of(mapper.mapRow(acknowledgement, 0));
        }).when(jdbcTemplate).query(
                anyString(), any(RowMapper.class), any(Object[].class));
    }

    private ResultSet targetRow(
            String audience,
            String deliveryStatus,
            Instant dueAt,
            Instant acknowledgedAt,
            Instant escalatedAt) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(1)).thenReturn(UUID.randomUUID().toString());
        when(resultSet.getString(2)).thenReturn(audience);
        when(resultSet.getString(3)).thenReturn("ACCOUNT_CLOSURE_P0_OPENED");
        when(resultSet.getString(4)).thenReturn(deliveryStatus);
        when(resultSet.getTimestamp(5)).thenReturn(Timestamp.from(P0_OPENED_AT));
        when(resultSet.getTimestamp(6)).thenReturn(Timestamp.from(dueAt));
        when(resultSet.getTimestamp(7)).thenReturn(timestamp(acknowledgedAt));
        when(resultSet.getTimestamp(8)).thenReturn(timestamp(escalatedAt));
        return resultSet;
    }

    private ResultSet acknowledgementRow(
            byte[] idempotencyKeyHash,
            byte[] requestHash) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(1)).thenReturn(UUID.randomUUID().toString());
        when(resultSet.getString(2)).thenReturn("ON_CALL");
        when(resultSet.getString(3)).thenReturn("TIMELY");
        when(resultSet.getTimestamp(4)).thenReturn(Timestamp.from(DUE_AT));
        when(resultSet.getTimestamp(5)).thenReturn(Timestamp.from(NOW));
        when(resultSet.getBytes(6)).thenReturn(idempotencyKeyHash);
        when(resultSet.getBytes(7)).thenReturn(requestHash);
        return resultSet;
    }

    private AccountClosureAlertAcknowledgementWrite write(
            AccountClosureAlertAudience audience,
            Instant acknowledgedAt) {
        return new AccountClosureAlertAcknowledgementWrite(
                UUID.randomUUID(),
                audience,
                new byte[32],
                new byte[32],
                new byte[32],
                new byte[32],
                acknowledgedAt);
    }

    private List<String> captureSuccessfulUpdates(JdbcTemplate jdbcTemplate) {
        List<String> updates = new ArrayList<>();
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            updates.add(invocation.getArgument(0));
            return 1;
        });
        return updates;
    }

    private JdbcAccountClosureAlertAcknowledgementRepositoryAdapter adapter(
            JdbcTemplate jdbcTemplate) {
        return new JdbcAccountClosureAlertAcknowledgementRepositoryAdapter(jdbcTemplate);
    }

    private void assertInvocationContains(JdbcTemplate jdbcTemplate, String expected) {
        boolean found = mockingDetails(jdbcTemplate).getInvocations().stream()
                .flatMap(invocation -> Arrays.stream(invocation.getArguments()))
                .map(argument -> argument == null ? "null" : argument.toString())
                .anyMatch(argument -> argument.contains(expected));
        assertTrue(found);
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
