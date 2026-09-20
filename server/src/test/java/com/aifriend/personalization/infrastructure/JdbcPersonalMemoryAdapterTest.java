package com.aifriend.personalization.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aifriend.personalization.domain.PersonalMemoryRecord;
import com.aifriend.personalization.domain.PersonalMemoryStatus;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

class JdbcPersonalMemoryAdapterTest {

    private static final UUID OWNER = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-09-04T13:00:00Z");

    @Test
    void shouldMapConcurrentFirstInsertToStableConflict() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        JdbcPersonalMemoryAdapter adapter = new JdbcPersonalMemoryAdapter(jdbcTemplate);

        assertThatThrownBy(() -> adapter.save(activeRecord(1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.SESSION_CONFLICT));
    }

    @Test
    void shouldUsePreviousVersionInAtomicUpdatePredicate() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcPersonalMemoryAdapter adapter = new JdbcPersonalMemoryAdapter(jdbcTemplate);

        PersonalMemoryRecord saved = adapter.save(activeRecord(4));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), arguments.capture());
        assertThat(sql.getValue()).contains("AND version=?");
        assertThat(arguments.getValue()).endsWith(OWNER.toString(), 3L);
        assertThat(saved.version()).isEqualTo(4);
    }

    private PersonalMemoryRecord activeRecord(long version) {
        return new PersonalMemoryRecord(
                OWNER, new byte[]{1, 2}, new byte[32], "personal-memory-v1",
                PersonalMemoryStatus.ACTIVE, new byte[32], new byte[32],
                null, null, version, NOW.minusSeconds(30), NOW, null);
    }
}
