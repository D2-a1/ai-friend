package com.aifriend.voicecollection.infrastructure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcVoiceTrainingDatasetCleanupAdapterTest {

    @Test
    void shouldDeleteAllOrContainingDatasetsWithinOwnerScope() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcVoiceTrainingDatasetCleanupAdapter adapter =
                new JdbcVoiceTrainingDatasetCleanupAdapter(jdbcTemplate);
        UUID ownerUserId = UUID.randomUUID();
        UUID sampleId = UUID.randomUUID();

        adapter.deleteAll(ownerUserId);
        adapter.deleteContaining(ownerUserId, sampleId);

        verify(jdbcTemplate).update(
                contains("DELETE FROM voice_training_dataset WHERE owner_user_id"),
                eq(ownerUserId.toString()));
        verify(jdbcTemplate).update(
                contains("JOIN voice_training_dataset_member"),
                eq(ownerUserId.toString()),
                eq(sampleId.toString()));
    }
}
