package com.aifriend.personalization.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aifriend.personalization.application.PersonalMemoryService;
import com.aifriend.personalization.application.PersonalMemorySnapshot;
import com.aifriend.personalization.domain.AmbiguousCallPreference;
import com.aifriend.personalization.domain.DialogueStylePreference;
import com.aifriend.personalization.domain.PersonalMemoryPreferences;
import com.aifriend.personalization.domain.SpeechRatePreference;
import com.aifriend.task.application.TaskConversationPreferences;

class PersonalMemoryTaskPersonalizationAdapterTest {

    private static final UUID OWNER = UUID.fromString(
            "20000000-0000-0000-0000-000000000001");

    private PersonalMemoryService service;
    private PersonalMemoryTaskPersonalizationAdapter adapter;

    @BeforeEach
    void setUp() {
        service = mock(PersonalMemoryService.class);
        adapter = new PersonalMemoryTaskPersonalizationAdapter(service);
    }

    @Test
    void activeConsentedMemoryMapsOnlyThreeFinitePreferences() {
        when(service.get(OWNER)).thenReturn(snapshot(true, true, preferences()));

        TaskConversationPreferences result = adapter.currentPreferences(OWNER);

        assertThat(result).isEqualTo(
                new TaskConversationPreferences("SLOW", "BRIEF", "VIDEO"));
    }

    @Test
    void disabledOrNotConsentedMemoryUsesSafeDefaults() {
        when(service.get(OWNER))
                .thenReturn(snapshot(false, true, preferences()))
                .thenReturn(snapshot(true, false, preferences()));

        assertThat(adapter.currentPreferences(OWNER))
                .isEqualTo(TaskConversationPreferences.safeDefaults());
        assertThat(adapter.currentPreferences(OWNER))
                .isEqualTo(TaskConversationPreferences.safeDefaults());
    }

    @Test
    void missingOrUnreadableMemoryUsesSafeDefaultsWithoutBlockingTask() {
        when(service.get(OWNER))
                .thenReturn(snapshot(true, true, null))
                .thenThrow(new IllegalStateException("cannot decode"));

        assertThat(adapter.currentPreferences(OWNER))
                .isEqualTo(TaskConversationPreferences.safeDefaults());
        assertThat(adapter.currentPreferences(OWNER))
                .isEqualTo(TaskConversationPreferences.safeDefaults());
    }

    private PersonalMemorySnapshot snapshot(
            boolean enabled,
            boolean consented,
            PersonalMemoryPreferences preferences) {
        return new PersonalMemorySnapshot(
                enabled, consented, "personal-memory-v1", preferences,
                preferences == null ? null : 1L,
                preferences == null ? null : Instant.parse("2026-09-04T00:00:00Z"));
    }

    private PersonalMemoryPreferences preferences() {
        return new PersonalMemoryPreferences(
                SpeechRatePreference.SLOW,
                DialogueStylePreference.BRIEF,
                AmbiguousCallPreference.VIDEO);
    }
}