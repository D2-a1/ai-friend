package com.aifriend.assistant.application;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class KnowledgeAnswerDeadlineTest {
    @Test void boundaryIsStrictAndSubMillisecondBudgetDoesNotStartACall() {
        var now = Instant.parse("2026-09-10T08:00:00Z");
        var ticks = new AtomicLong();
        var deadline = new KnowledgeAnswerDeadline(now.plusSeconds(8), Clock.fixed(now, ZoneOffset.UTC), ticks::get);
        ticks.set(Duration.ofSeconds(8).minusMillis(1).toNanos());
        assertThat(deadline.remaining(Duration.ofSeconds(4))).isEqualTo(Duration.ofMillis(1));
        ticks.incrementAndGet();
        assertThatThrownBy(deadline::check).isInstanceOf(KnowledgeAnswerDeadline.Expired.class);
        ticks.set(Duration.ofSeconds(8).toNanos());
        assertThatThrownBy(deadline::check).isInstanceOf(KnowledgeAnswerDeadline.Expired.class);
    }

    @Test void invalidExtendedDeadlineAndAlreadyExpiredDeadlineAreRejected() {
        var now = Instant.parse("2026-09-10T08:00:00Z");
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        assertThatThrownBy(() -> new KnowledgeAnswerDeadline(now.plusMillis(8001), clock, () -> 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeAnswerDeadline(now, clock, () -> 0).check())
                .isInstanceOf(KnowledgeAnswerDeadline.Expired.class);
        assertThatThrownBy(() -> new KnowledgeAnswerDeadline(now.minusSeconds(1), clock, () -> 0).check())
                .isInstanceOf(KnowledgeAnswerDeadline.Expired.class);
    }

    @Test void invalidSettingsAndRequestFieldsFailBeforeWork() {
        assertThatThrownBy(() -> new KnowledgeAnswerService.Settings(
                com.aifriend.assistant.domain.AssistantAnswer.Mode.TEMPLATE, Duration.ofSeconds(4)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeAnswerService.Settings(
                com.aifriend.assistant.domain.AssistantAnswer.Mode.GENERATED, Duration.ofMillis(4001)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
