package com.aifriend.assistant.application;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.assistant.domain.AssistantTurnRequest;
import com.aifriend.assistant.domain.AssistantTurnRequest.State;

class AssistantExecutionBudgetTest {
    @Test void reproducesWhyInterpretingDatabaseDeadlineWithApplicationClockIsWrong() {
        Instant localNow = Instant.parse("2026-09-13T00:00:00Z");
        var localClock = Clock.fixed(localNow, ZoneOffset.UTC);
        var databaseBehind = request(localNow.minusMillis(5400));
        var oldBudget = new KnowledgeAnswerDeadline(databaseBehind.deadline(), localClock, () -> 0);
        assertThat(oldBudget.remaining(Duration.ofSeconds(8))).isEqualTo(Duration.ofMillis(2600));
        var databaseAhead = request(localNow.plusMillis(5400));
        assertThatThrownBy(() -> new KnowledgeAnswerDeadline(databaseAhead.deadline(), localClock, () -> 0))
                .hasMessage("INVALID_QUESTION_DEADLINE");
        assertThat(new AssistantExecutionBudget(databaseBehind, 0, () -> 0).remaining()).isEqualTo(Duration.ofSeconds(8));
        assertThat(new AssistantExecutionBudget(databaseAhead, 0, () -> 0).remaining()).isEqualTo(Duration.ofSeconds(8));
    }

    private AssistantTurnRequest request(Instant databaseNow) {
        return new AssistantTurnRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Purpose.CONTACT_GRAPH, "a".repeat(64), "b".repeat(64), 1, UUID.randomUUID(),
                databaseNow, databaseNow.plusSeconds(8), databaseNow.plusSeconds(900),
                State.PROCESSING, null, null);
    }

    @Test void databaseClockOffsetNeverChangesMonotonicBudgetOrPersistentDeadline() {
        Instant localNow = Instant.parse("2026-09-13T00:00:00Z");
        for (long offset : new long[] {-5400, 0, 5400}) {
            var request = request(localNow.plusMillis(offset));
            var ticks = new AtomicLong(Duration.ofMillis(1700).toNanos());
            var budget = new AssistantExecutionBudget(request, 0, ticks::get);
            assertThat(budget.remaining()).isEqualTo(Duration.ofMillis(6300));
            assertThat(request.deadline()).isEqualTo(localNow.plusMillis(offset).plusSeconds(8));
            ticks.addAndGet(Duration.ofMillis(300).toNanos());
            assertThat(budget.remaining()).isEqualTo(Duration.ofSeconds(6));
        }
    }

    @Test void admissionAndQueueDelayNeverResetTheBudget() {
        var ticks = new AtomicLong(Duration.ofSeconds(7).toNanos());
        var budget = new AssistantExecutionBudget(request(Instant.EPOCH), 0, ticks::get);
        assertThat(budget.remaining()).isEqualTo(Duration.ofSeconds(1));
        ticks.addAndGet(Duration.ofSeconds(1).toNanos());
        assertThatThrownBy(budget::remaining).isInstanceOf(AssistantExecutionPort.Expired.class);
        // 在同一起点重建也不能重新获得八秒。
        var rebuilt = new AssistantExecutionBudget(request(Instant.EPOCH), 0, ticks::get);
        assertThatThrownBy(rebuilt::remaining).isInstanceOf(AssistantExecutionPort.Expired.class);
    }

    @Test void negativeTickerAndSubMillisecondRemainderFailClosed() {
        var ticks = new AtomicLong(-1);
        var budget = new AssistantExecutionBudget(request(Instant.EPOCH), 0, ticks::get);
        assertThatThrownBy(budget::remaining).isInstanceOf(AssistantExecutionPort.Expired.class);
        ticks.set(Duration.ofSeconds(8).minusMillis(1).toNanos());
        assertThat(budget.remaining()).isEqualTo(Duration.ofMillis(1));
        ticks.incrementAndGet();
        assertThatThrownBy(budget::remaining).isInstanceOf(AssistantExecutionPort.Expired.class);
    }

    @Test void terminalRequestCannotAcquireAnExecutionBudget() {
        var original = request(Instant.EPOCH);
        var terminal = new AssistantTurnRequest(original.id(), original.owner(), original.sessionId(),
                original.purpose(), original.keyHash(), original.requestDigest(), original.admittedVersion(),
                original.leaseToken(), original.createdAt(), original.deadline(), original.expiresAt(),
                State.EXPIRED, null, null);
        assertThatThrownBy(() -> new AssistantExecutionBudget(terminal, 0, () -> 0))
                .hasMessage("INVALID_EXECUTION_BUDGET_STATE");
    }
}
