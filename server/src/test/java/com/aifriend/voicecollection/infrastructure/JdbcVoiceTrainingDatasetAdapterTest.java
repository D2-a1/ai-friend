package com.aifriend.voicecollection.infrastructure;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcVoiceTrainingDatasetAdapterTest {

    @Test
    void shouldReadExportMembersWithinOwnerScopeAndFrozenOrder() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(
                any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        JdbcVoiceTrainingDatasetAdapter adapter =
                new JdbcVoiceTrainingDatasetAdapter(jdbcTemplate);
        UUID ownerUserId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();

        adapter.findExportMembers(ownerUserId, datasetId, 501);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sql.capture(),
                any(RowMapper.class),
                eq(datasetId.toString()),
                eq(ownerUserId.toString()),
                eq(501));
        assertTrue(sql.getValue().contains("dataset_row.owner_user_id=UUID_TO_BIN(?)"));
        assertTrue(sql.getValue().contains("ORDER BY dataset_member.member_order LIMIT ?"));
    }
}
