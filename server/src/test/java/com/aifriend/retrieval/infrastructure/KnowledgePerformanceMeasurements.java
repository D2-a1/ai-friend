package com.aifriend.retrieval.infrastructure;

import java.util.Arrays;

/** 性能专项统计；失败耗时也进入分位数，未完成样本不得静默省略。 */
final class KnowledgePerformanceMeasurements {
    private KnowledgePerformanceMeasurements() { }

    static Stats summarize(long[] nanos, boolean[] successful, long deadlineNanos) {
        if (nanos.length == 0 || nanos.length != successful.length || deadlineNanos <= 0) {
            throw new IllegalArgumentException("INVALID_MEASUREMENTS");
        }
        var ordered = nanos.clone();
        int failed = 0;
        int over = 0;
        for (int i = 0; i < nanos.length; i++) {
            if (nanos[i] < 0) { throw new IllegalArgumentException("INCOMPLETE_MEASUREMENTS"); }
            if (!successful[i]) { failed++; }
            if (nanos[i] > deadlineNanos) { over++; }
        }
        Arrays.sort(ordered);
        return new Stats(nanos.length, failed, over, percentile(ordered, .5), percentile(ordered, .95),
                percentile(ordered, .99), ordered[ordered.length - 1] / 1_000_000d);
    }

    private static double percentile(long[] ordered, double percentile) {
        return ordered[(int) Math.ceil(ordered.length * percentile) - 1] / 1_000_000d;
    }

    record Stats(int samples, int failures, int overDeadline, double p50Ms, double p95Ms, double p99Ms, double maxMs) { }
}
