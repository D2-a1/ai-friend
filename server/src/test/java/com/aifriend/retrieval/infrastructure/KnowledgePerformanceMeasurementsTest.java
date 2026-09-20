package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import java.util.Arrays;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class KnowledgePerformanceMeasurementsTest {
    @Test void slowFailureRemainsInNearestRankPercentilesAndDeadlineCount() {
        var times = LongStream.rangeClosed(1, 100).map(n -> n * 1_000_000).toArray();
        var passed = new boolean[100]; Arrays.fill(passed, true); passed[99] = false;
        var result = KnowledgePerformanceMeasurements.summarize(times, passed, 50_000_000);
        assertThat(result.samples()).isEqualTo(100); assertThat(result.failures()).isEqualTo(1);
        assertThat(result.overDeadline()).isEqualTo(50);
        assertThat(result.p50Ms()).isEqualTo(50); assertThat(result.p95Ms()).isEqualTo(95);
        assertThat(result.p99Ms()).isEqualTo(99); assertThat(result.maxMs()).isEqualTo(100);
    }
    @Test void missingOrMalformedMeasurementsCannotAppearAsPassingReport() {
        assertThatThrownBy(() -> KnowledgePerformanceMeasurements.summarize(new long[] {-1}, new boolean[] {true}, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgePerformanceMeasurements.summarize(new long[0], new boolean[0], 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgePerformanceMeasurements.summarize(new long[] {1}, new boolean[0], 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
