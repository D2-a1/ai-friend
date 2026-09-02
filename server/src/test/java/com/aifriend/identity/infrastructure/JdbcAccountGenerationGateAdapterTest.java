package com.aifriend.identity.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

class JdbcAccountGenerationGateAdapterTest {
    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

    @Test
    void shouldReturnFirstGenerationWhenNoTombstoneExists() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubTombstoneQuery(jdbcTemplate, List.of());
        JdbcAccountGenerationGateAdapter adapter =
                new JdbcAccountGenerationGateAdapter(jdbcTemplate);

        assertEquals(1L, adapter.nextGeneration(new byte[32], NOW));
    }

    @Test
    void shouldReturnNextGenerationWhenDeadlineHasArrived() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet resultSet = tombstoneResult(3L, NOW.minusSeconds(1));
        stubTombstoneQuery(jdbcTemplate, List.of(resultSet));
        JdbcAccountGenerationGateAdapter adapter =
                new JdbcAccountGenerationGateAdapter(jdbcTemplate);

        assertEquals(4L, adapter.nextGeneration(new byte[32], NOW));
    }

    @Test
    void shouldRejectReRegistrationBeforeDeadline() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet resultSet = tombstoneResult(1L, NOW.plusSeconds(1));
        stubTombstoneQuery(jdbcTemplate, List.of(resultSet));
        JdbcAccountGenerationGateAdapter adapter =
                new JdbcAccountGenerationGateAdapter(jdbcTemplate);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> adapter.nextGeneration(new byte[32], NOW));

        assertEquals(ErrorCode.ACCOUNT_CLOSURE_ACCEPTED, exception.errorCode());
    }

    private ResultSet tombstoneResult(long generation, Instant deadline) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        org.mockito.Mockito.when(resultSet.getLong("old_account_generation"))
                .thenReturn(generation);
        org.mockito.Mockito.when(resultSet.getTimestamp("re_registration_not_before"))
                .thenReturn(Timestamp.from(deadline));
        return resultSet;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubTombstoneQuery(JdbcTemplate jdbcTemplate, List<ResultSet> rows) {
        doAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            java.util.ArrayList<Object> mapped = new java.util.ArrayList<>();
            for (int index = 0; index < rows.size(); index++) {
                mapped.add(mapper.mapRow(rows.get(index), index));
            }
            return mapped;
        }).when(jdbcTemplate).query(
                anyString(), any(RowMapper.class), any(Object[].class));
    }
}
