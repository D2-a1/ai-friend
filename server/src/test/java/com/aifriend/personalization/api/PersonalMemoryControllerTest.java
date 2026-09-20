package com.aifriend.personalization.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import com.aifriend.personalization.application.PersonalMemoryService;
import com.aifriend.personalization.application.PersonalMemorySnapshot;
import com.aifriend.personalization.domain.AmbiguousCallPreference;
import com.aifriend.personalization.domain.DialogueStylePreference;
import com.aifriend.personalization.domain.PersonalMemoryPreferences;
import com.aifriend.personalization.domain.SpeechRatePreference;
import com.aifriend.shared.api.ApiResponse;
import com.aifriend.shared.security.PublicIdCodec;

class PersonalMemoryControllerTest {

    @Test
    void shouldUseAuthenticatedOwnerAndMapConstrainedPreferences() {
        UUID owner = UUID.randomUUID();
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(PublicIdCodec.userId(owner));
        PersonalMemoryService service = mock(PersonalMemoryService.class);
        Instant updatedAt = Instant.parse("2026-09-04T16:00:00Z");
        PersonalMemoryPreferences preferences = new PersonalMemoryPreferences(
                SpeechRatePreference.NORMAL,
                DialogueStylePreference.STANDARD,
                AmbiguousCallPreference.ASK_EVERY_TIME);
        when(service.update(eq(owner), eq("01JPERSONALMEMORYUPDATE000002"),
                eq(preferences), eq(0L))).thenReturn(new PersonalMemorySnapshot(
                        false, true, "personal-memory-v1", preferences, 1L, updatedAt));
        PersonalMemoryController controller = new PersonalMemoryController(service);

        ApiResponse<PersonalMemoryResp> response = controller.update(
                jwt,
                "01JPERSONALMEMORYUPDATE000002",
                new UpdatePersonalMemoryReq(
                        SpeechRatePreference.NORMAL,
                        DialogueStylePreference.STANDARD,
                        AmbiguousCallPreference.ASK_EVERY_TIME,
                        0));

        assertThat(response.data().featureEnabled()).isFalse();
        assertThat(response.data().preferences().speechRate())
                .isEqualTo(SpeechRatePreference.NORMAL);
        assertThat(response.data().version()).isEqualTo(1L);
        verify(service).update(owner, "01JPERSONALMEMORYUPDATE000002", preferences, 0);
    }
}
