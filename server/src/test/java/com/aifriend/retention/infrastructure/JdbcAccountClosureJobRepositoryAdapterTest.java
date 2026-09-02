package com.aifriend.retention.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcAccountClosureJobRepositoryAdapterTest {
    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

    @Test
    void shouldAtomicallyPublishWarningAndP0AfterFortyEightHours() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet resultSet = alertRow(
                NOW.minusSeconds(49 * 60 * 60), null, null, null, null, null, null);
        stubAlertQuery(jdbcTemplate, resultSet);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcAccountClosureJobRepositoryAdapter adapter =
                new JdbcAccountClosureJobRepositoryAdapter(jdbcTemplate);

        int published = adapter.publishDueAlerts(NOW, 50);

        assertEquals(2, published);
        assertTrue(invocationContains(jdbcTemplate, "ACCOUNT_CLOSURE_DELAY_WARNING"));
        assertTrue(invocationContains(jdbcTemplate, "ACCOUNT_CLOSURE_P0_OPENED"));
        assertTrue(invocationContains(jdbcTemplate, NOW.plusSeconds(15 * 60).toString()));
    }

    @Test
    void shouldEscalateUnacknowledgedP0AfterFifteenMinutes() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet resultSet = alertRow(
                NOW.minusSeconds(50 * 60 * 60),
                NOW.minusSeconds(26 * 60 * 60),
                NOW.minusSeconds(20 * 60),
                NOW.minusSeconds(5 * 60),
                null,
                null,
                null);
        stubAlertQuery(jdbcTemplate, resultSet);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcAccountClosureJobRepositoryAdapter adapter =
                new JdbcAccountClosureJobRepositoryAdapter(jdbcTemplate);

        int published = adapter.publishDueAlerts(NOW, 50);

        assertEquals(1, published);
        assertTrue(invocationContains(jdbcTemplate, "ACCOUNT_CLOSURE_P0_ESCALATED"));
    }

    @Test
    void shouldPublishDeadlineBreachAfterSeventyTwoHours() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet resultSet = alertRow(
                NOW.minusSeconds(73 * 60 * 60),
                NOW.minusSeconds(49 * 60 * 60),
                NOW.minusSeconds(25 * 60 * 60),
                NOW.minusSeconds(24 * 60 * 60),
                null,
                NOW.minusSeconds(23 * 60 * 60),
                null);
        stubAlertQuery(jdbcTemplate, resultSet);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcAccountClosureJobRepositoryAdapter adapter =
                new JdbcAccountClosureJobRepositoryAdapter(jdbcTemplate);

        int published = adapter.publishDueAlerts(NOW, 50);

        assertEquals(1, published);
        assertTrue(invocationContains(
                jdbcTemplate, "ACCOUNT_CLOSURE_DEADLINE_BREACHED"));
    }

    private ResultSet alertRow(
            Instant acceptedAt,
            Instant warningAt,
            Instant p0OpenedAt,
            Instant acknowledgementDueAt,
            Instant acknowledgedAt,
            Instant escalatedAt,
            Instant deadlineBreachedAt) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(1)).thenReturn(UUID.randomUUID().toString());
        when(resultSet.getTimestamp(2)).thenReturn(Timestamp.from(acceptedAt));
        when(resultSet.getTimestamp(3)).thenReturn(timestamp(warningAt));
        when(resultSet.getTimestamp(4)).thenReturn(timestamp(p0OpenedAt));
        when(resultSet.getTimestamp(5)).thenReturn(timestamp(acknowledgementDueAt));
        when(resultSet.getTimestamp(6)).thenReturn(timestamp(acknowledgedAt));
        when(resultSet.getTimestamp(7)).thenReturn(timestamp(escalatedAt));
        when(resultSet.getTimestamp(8)).thenReturn(timestamp(deadlineBreachedAt));
        return resultSet;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubAlertQuery(JdbcTemplate jdbcTemplate, ResultSet resultSet) {
        doAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbcTemplate).query(
                anyString(), any(RowMapper.class), any(Object[].class));
    }

    private boolean invocationContains(JdbcTemplate jdbcTemplate, String expected) {
        return mockingDetails(jdbcTemplate).getInvocations().stream()
                .flatMap(invocation -> Arrays.stream(invocation.getArguments()))
                .anyMatch(argument -> expected.equals(argument));
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
