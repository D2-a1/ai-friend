package com.aifriend.personalization.infrastructure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aifriend.consent.domain.ConsentType;

class PersonalMemoryConsentRevocationHandlerTest {

    private static final Instant NOW = Instant.parse("2026-09-04T14:00:00Z");

    @Test
    void shouldImmediatelyEraseContentForPersonalMemoryRevocation() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PersonalMemoryConsentRevocationHandler handler =
                new PersonalMemoryConsentRevocationHandler(jdbcTemplate);
        UUID owner = UUID.randomUUID();

        handler.cleanup(owner, ConsentType.PERSONAL_MEMORY, NOW);

        verify(jdbcTemplate).update(
                contains("preferences_cipher=NULL"), any(Object[].class));
        verify(jdbcTemplate).update(
                contains("status='DELETED'"), any(Object[].class));
    }

    @Test
    void shouldIgnoreUnrelatedConsentType() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PersonalMemoryConsentRevocationHandler handler =
                new PersonalMemoryConsentRevocationHandler(jdbcTemplate);

        handler.cleanup(UUID.randomUUID(), ConsentType.MICROPHONE, NOW);

        verify(jdbcTemplate, never()).update(any(String.class), any(Object[].class));
    }
}
