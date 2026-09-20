package com.aifriend.retrieval.application;

import static org.assertj.core.api.Assertions.*;
import static com.aifriend.retrieval.application.KnowledgeCleanupRetryPort.AlertLevel.*;
import static com.aifriend.retrieval.application.KnowledgeCleanupRetryPort.Outcome.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeCleanupRetryStateTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID TOKEN = new UUID(0, 1);

    @Test void firstThreeFailuresBackOffThenContinueEveryFiveMinutesWithoutDroppingPending() {
        var state = KnowledgeCleanupRetryState.initial(START);
        long[] delays = {1, 5, 15, 300, 300, 300};
        for (int i = 0; i < delays.length; i++) {
            state = state.observe(state.nextAttempt()).acquire(TOKEN).finish(TOKEN, FAILED);
            assertThat(state.failures()).isEqualTo(i + 1);
            assertThat(state.pendingSince()).isEqualTo(START);
            assertThat(state.firstFailures()).isEqualTo(1);
            assertThat(state.nextAttempt()).isEqualTo(state.lastSeen().plusSeconds(delays[i]));
            assertThat(state.acquire(TOKEN)).isEqualTo(state);
        }
    }

    @Test void exactlyFifteenMinutesEmitsOneEscalationEvenWhileWaitingForAnotherRetry() {
        var state = KnowledgeCleanupRetryState.initial(START).acquire(TOKEN).finish(TOKEN, FAILED);
        assertThat(state.observe(START.plusSeconds(899)).escalations()).isZero();
        state = state.observe(START.plusSeconds(900));
        assertThat(state.escalated()).isTrue();
        assertThat(state.escalations()).isEqualTo(1);
        assertThat(state.observe(START.plusSeconds(1800)).escalations()).isEqualTo(1);
    }

    @Test void progressedAndBusyAreNotProofOfDrainAndDoNotClearFailureOrAlerts() {
        for (var outcome : new KnowledgeCleanupRetryPort.Outcome[]{PROGRESSED, DEFERRED}) {
            var state = KnowledgeCleanupRetryState.initial(START).acquire(TOKEN).finish(TOKEN, FAILED)
                    .observe(START.plusSeconds(900)).acquire(TOKEN).finish(TOKEN, outcome);
            assertThat(state.pendingSince()).isEqualTo(START);
            assertThat(state.failures()).isEqualTo(1);
            assertThat(state.escalated()).isTrue();
            assertThat(state.firstAcknowledged()).isZero();
            assertThat(state.escalationAcknowledged()).isZero();
        }
    }

    @Test void drainedRetainsUnsentAlertsAndNewEpisodeCannotBeAcknowledgedByOldReceipt() {
        var state = KnowledgeCleanupRetryState.initial(START).acquire(TOKEN).finish(TOKEN, FAILED)
                .observe(START.plusSeconds(900)).acquire(TOKEN).finish(TOKEN, DRAINED);
        assertThat(state.pendingSince()).isNull();
        assertThat(state.failures()).isZero();
        assertThat(state.firstFailures()).isEqualTo(1);
        assertThat(state.escalations()).isEqualTo(1);
        state = state.observe(state.nextAttempt()).acquire(TOKEN).finish(TOKEN, FAILED);
        state = state.acknowledge(FIRST_FAILURE, 1).acknowledge(OVERDUE, 1);
        assertThat(state.firstFailures() - state.firstAcknowledged()).isEqualTo(1);
        assertThat(state.acknowledge(FIRST_FAILURE, 1)).isEqualTo(state);
        assertThat(state.acknowledge(FIRST_FAILURE, 3)).isEqualTo(state);
        assertThat(state.acknowledge(OVERDUE, -1)).isEqualTo(state);
    }

    @Test void lostWorkerIsDurableFailureAndOldCompletionCannotClaimSuccess() {
        var state = KnowledgeCleanupRetryState.initial(START).acquire(TOKEN);
        assertThat(state.observe(START.plusSeconds(29)).leaseToken()).isEqualTo(TOKEN);
        state = state.observe(START.plusSeconds(30));
        assertThat(state.leaseToken()).isNull();
        assertThat(state.pendingSince()).isEqualTo(START.plusSeconds(30));
        assertThat(state.finish(TOKEN, DRAINED)).isEqualTo(state);
        assertThat(state.acquire(new UUID(0, 2))).isEqualTo(state);
        state = state.observe(START.plusSeconds(31)).acquire(new UUID(0, 2));
        assertThat(state.finish(TOKEN, FAILED)).isEqualTo(state);
    }

    @Test void backwardsClockAndCorruptStateFailClosedWithoutReset() {
        var state = KnowledgeCleanupRetryState.initial(START).acquire(TOKEN);
        assertThatThrownBy(() -> state.observe(START.minusMillis(1))).hasMessage("CLEANUP_CLOCK_UNRELIABLE");
        assertThatThrownBy(() -> new KnowledgeCleanupRetryState(1, null, START, START, null, null,
                false, 0, 0, 0, 0)).hasMessage("INVALID_CLEANUP_RETRY_STATE");
        assertThatThrownBy(() -> new KnowledgeCleanupRetryState(0, null, START, START, null, null,
                false, 0, 1, 0, 0)).hasMessage("INVALID_CLEANUP_RETRY_STATE");
    }

    @Test void failureCountSaturatesButNeverSwitchesToIdleOrWrapsRetryBudget() {
        var state = new KnowledgeCleanupRetryState(Integer.MAX_VALUE, START, START, START, null, null,
                false, 1, 0, 0, 0).acquire(TOKEN).finish(TOKEN, FAILED);
        assertThat(state.failures()).isEqualTo(Integer.MAX_VALUE);
        assertThat(state.nextAttempt()).isEqualTo(START.plusSeconds(300));
        assertThat(state.pendingSince()).isEqualTo(START);
    }
}
