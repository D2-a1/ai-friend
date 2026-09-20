package com.aifriend.retrieval.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.aifriend.retrieval.domain.KnowledgeImportJob.Failure;
import com.aifriend.retrieval.domain.KnowledgeImportJob.State;

class KnowledgeImportJobTest {
    private final Instant now = Instant.parse("2026-09-09T00:00:00Z");
    private final UUID first = new UUID(0, 1);
    private final UUID second = new UUID(0, 2);
    private KnowledgeImportJob pending() {
        return KnowledgeImportJob.pending(new UUID(0, 3), now, now.plusSeconds(600));
    }

    @Test void successfulClaimAndCompleteAdvanceVersionAndClearLease() {
        var claimed = pending().claim(now, first, Duration.ofSeconds(30));
        assertThat(claimed.attempts()).isEqualTo(1);
        assertThat(claimed.version()).isEqualTo(2);
        var done = claimed.complete(now.plusSeconds(1), first);
        assertThat(done.state()).isEqualTo(State.READY);
        assertThat(done.version()).isEqualTo(3);
        assertThat(done.leaseToken()).isEmpty();
        assertThatIllegalStateException().isThrownBy(() -> done.claim(now, second, Duration.ofSeconds(30)));
    }

    @Test void expiryAtExactBoundaryInvalidatesOldTokenAndAllowsNewClaim() {
        var job = pending().claim(now, first, Duration.ofSeconds(30));
        assertThatIllegalStateException().isThrownBy(() -> job.claim(now.plusMillis(29999), second, Duration.ofSeconds(30)));
        assertThat(job.complete(now.plusMillis(29999), first).state()).isEqualTo(State.READY);
        assertThatIllegalStateException().isThrownBy(() -> job.complete(now.plusSeconds(30), first));
        var replacement = job.claim(now.plusSeconds(30), second, Duration.ofSeconds(30));
        assertThat(replacement.attempts()).isEqualTo(2);
        assertThatIllegalStateException().isThrownBy(() -> replacement.complete(now.plusSeconds(31), first));
        assertThat(replacement.complete(now.plusSeconds(31), second).state()).isEqualTo(State.READY);
    }

    @Test void retryBackoffAndThirdAttemptLimitSurviveReconstruction() {
        var one = pending().claim(now, first, Duration.ofSeconds(30))
                .fail(now, first, Failure.TEMPORARY, true);
        assertThat(one.nextAttemptAt()).isEqualTo(now.plusSeconds(1));
        assertThatIllegalStateException().isThrownBy(() -> one.claim(now, second, Duration.ofSeconds(30)));
        var two = one.claim(now.plusSeconds(1), second, Duration.ofSeconds(30))
                .fail(now.plusSeconds(1), second, Failure.SOURCE_CHANGED, true);
        var restored = new KnowledgeImportJob(two.id(), two.state(), two.attempts(), two.version(),
                two.leaseToken(), two.leaseUntil(), two.nextAttemptAt(), two.deadline(), two.failure());
        assertThat(restored.nextAttemptAt()).isEqualTo(now.plusSeconds(6));
        var exhausted = restored.claim(now.plusSeconds(6), first, Duration.ofSeconds(30))
                .fail(now.plusSeconds(6), first, Failure.TEMPORARY, true);
        assertThat(exhausted.state()).isEqualTo(State.FAILED);
        assertThat(exhausted.attempts()).isEqualTo(3);
        assertThatIllegalStateException().isThrownBy(() -> exhausted.claim(now.plusSeconds(7), second, Duration.ofSeconds(30)));
    }

    @Test void lastCrashedWorkerIsEventuallyTerminalNotPermanentlyProcessing() {
        var job = pending().claim(now, first, Duration.ofSeconds(1))
                .claim(now.plusSeconds(1), second, Duration.ofSeconds(1))
                .claim(now.plusSeconds(2), first, Duration.ofSeconds(1));
        assertThat(job.expire(now.plusMillis(2999))).isSameAs(job);
        assertThat(job.expire(now.plusSeconds(3)).failure()).isEqualTo(Failure.ATTEMPTS_EXHAUSTED);
    }

    @Test void totalDeadlineCapsLeaseAndPreventsCompletionOrRetryAtExpiry() {
        var job = KnowledgeImportJob.pending(first, now, now.plusSeconds(1))
                .claim(now, second, Duration.ofSeconds(30));
        assertThat(job.leaseUntil()).contains(now.plusSeconds(1));
        assertThatIllegalStateException().isThrownBy(() -> job.complete(now.plusSeconds(1), second));
        assertThat(job.expire(now.plusSeconds(1)).failure()).isEqualTo(Failure.DEADLINE);
        assertThat(job.fail(now, second, Failure.TEMPORARY, true).state()).isEqualTo(State.FAILED);
    }

    @Test void permanentFailureIsNotRetryableAndWrongWorkerCannotChangeState() {
        var job = pending().claim(now, first, Duration.ofSeconds(30));
        assertThatIllegalStateException().isThrownBy(() -> job.fail(now, second, Failure.TEMPORARY, true));
        assertThatIllegalArgumentException().isThrownBy(() -> job.fail(now, first, Failure.INDEX_INVALID, true));
        assertThat(job.fail(now, first, Failure.CONFIGURATION, false).state()).isEqualTo(State.FAILED);
    }

    @Test void invalidReconstructedStatesAndLeaseDurationsFailClosed() {
        assertThatIllegalArgumentException().isThrownBy(() -> new KnowledgeImportJob(first, State.PROCESSING,
                1, 1, Optional.empty(), Optional.empty(), now, now.plusSeconds(1), Failure.NONE));
        assertThatIllegalArgumentException().isThrownBy(() -> new KnowledgeImportJob(first, State.PENDING,
                3, 1, Optional.empty(), Optional.empty(), now, now.plusSeconds(1), Failure.NONE));
        for (Duration duration : new Duration[] {Duration.ZERO, Duration.ofMillis(-1), Duration.ofSeconds(301)}) {
            assertThatIllegalArgumentException().isThrownBy(() -> pending().claim(now, first, duration));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> KnowledgeImportJob.pending(first, now, now));
    }

    @Test void reclaimMustUseFreshTokenAndTerminalDoesNotExpireAgain() {
        var job = pending().claim(now, first, Duration.ofSeconds(1));
        assertThatIllegalStateException().isThrownBy(() -> job.claim(now.plusSeconds(1), first, Duration.ofSeconds(1)));
        var ready = job.complete(now, first);
        assertThat(ready.expire(now.plusSeconds(1000))).isSameAs(ready);
        assertThat(job.toString()).doesNotContain(first.toString());
    }
}
