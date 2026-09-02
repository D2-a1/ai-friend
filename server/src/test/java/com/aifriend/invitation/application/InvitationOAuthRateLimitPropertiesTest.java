package com.aifriend.invitation.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class InvitationOAuthRateLimitPropertiesTest {

    @Test
    void shouldAcceptFrozenFiveAttemptsPerFiveMinutes() {
        assertDoesNotThrow(() -> new InvitationOAuthRateLimitProperties(
                5, Duration.ofMinutes(5)));
    }

    @Test
    void shouldRejectDeploymentAttemptToWeakenFrozenLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> new InvitationOAuthRateLimitProperties(10, Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class,
                () -> new InvitationOAuthRateLimitProperties(5, Duration.ofMinutes(1)));
    }
}
