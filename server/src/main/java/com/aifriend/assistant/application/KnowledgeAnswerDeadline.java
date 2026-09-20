package com.aifriend.assistant.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.function.LongSupplier;

/** 单问题的不可续期预算，墙钟前移或单调时间耗尽都丢弃迟到结果。 */
final class KnowledgeAnswerDeadline {
    private final Clock clock;
    private final LongSupplier ticker;
    private final Instant expires;
    private final long started;
    private final long initialNanos;
    private final AssistantExecutionPort.Budget shared;

    KnowledgeAnswerDeadline(Instant expires, Clock clock, LongSupplier ticker) {
        this.shared = null;
        this.clock = clock;
        this.ticker = ticker;
        this.expires = expires;
        this.started = ticker.getAsLong();
        Duration initial = Duration.between(clock.instant(), expires);
        if (initial.compareTo(Duration.ofSeconds(8)) > 0) {
            throw new IllegalArgumentException("INVALID_QUESTION_DEADLINE");
        }
        this.initialNanos = initial.isNegative() ? 0 : initial.toNanos();
    }
    KnowledgeAnswerDeadline(AssistantExecutionPort.Budget shared) {
        this.shared = java.util.Objects.requireNonNull(shared);
        this.clock = null; this.ticker = null; this.expires = null;
        this.started = 0; this.initialNanos = 0;
    }

    Duration remaining(Duration cap) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("KNOWLEDGE_ANSWER_CANCELLED");
        }
        if (shared != null) {
            try {
                Duration value = shared.remaining();
                if (value.compareTo(Duration.ofMillis(1)) < 0) { throw new Expired(); }
                return value.compareTo(cap) < 0 ? value : cap;
            } catch (AssistantExecutionPort.Expired expired) { throw new Expired(); }
        }
        long elapsed = ticker.getAsLong() - started;
        if (elapsed < 0) { throw new Expired(); }
        Duration wall = Duration.between(clock.instant(), expires);
        if (wall.isNegative() || wall.isZero() || elapsed >= initialNanos) { throw new Expired(); }
        long nanos = Math.min(initialNanos - elapsed, cap.toNanos());
        // 比较Duration避免墙钟发生极端回拨时toNanos溢出。
        Duration remaining = Duration.ofNanos(nanos);
        if (wall.compareTo(remaining) < 0) { remaining = wall; }
        if (remaining.compareTo(Duration.ofMillis(1)) < 0) { throw new Expired(); }
        return remaining;
    }

    void check() { remaining(Duration.ofSeconds(8)); }

    static final class Expired extends RuntimeException {
        Expired() { super("KNOWLEDGE_ANSWER_DEADLINE"); }
    }
}
