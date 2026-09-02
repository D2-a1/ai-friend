package com.aifriend.voicecollection.infrastructure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aifriend.consent.domain.ConsentType;
import com.aifriend.voicecollection.application.VoiceTrainingDatasetCleanupPort;

class VoiceCollectionConsentRevocationAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-28T07:00:00Z");

    @Test
    void shouldDeleteDatasetsWhenTrainingConsentIsRevoked() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        VoiceTrainingDatasetCleanupPort cleanupPort =
                mock(VoiceTrainingDatasetCleanupPort.class);
        VoiceCollectionConsentRevocationAdapter adapter =
                new VoiceCollectionConsentRevocationAdapter(jdbcTemplate, cleanupPort);
        UUID ownerUserId = UUID.randomUUID();

        adapter.cleanup(ownerUserId, ConsentType.VOICE_MODEL_TRAINING, NOW);

        verify(cleanupPort).deleteAll(ownerUserId);
        verify(jdbcTemplate).update(
                contains("UPDATE voice_collection_sample SET training_eligible=FALSE"),
                any(Object[].class));
    }

    @Test
    void shouldDeleteDatasetsWhenCollectionConsentIsRevoked() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        VoiceTrainingDatasetCleanupPort cleanupPort =
                mock(VoiceTrainingDatasetCleanupPort.class);
        VoiceCollectionConsentRevocationAdapter adapter =
                new VoiceCollectionConsentRevocationAdapter(jdbcTemplate, cleanupPort);
        UUID ownerUserId = UUID.randomUUID();

        adapter.cleanup(ownerUserId, ConsentType.TEST_VOICE_COLLECTION, NOW);

        verify(cleanupPort).deleteAll(ownerUserId);
        verify(jdbcTemplate).update(
                contains("UPDATE voice_collection_sample SET category=NULL"),
                any(Object[].class));
    }
}
