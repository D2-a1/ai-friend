package com.aifriend.assistant.application;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;
import com.aifriend.assistant.domain.AssistantTurnRequest;

/**
 * 将数据库冻结的预算长度转换为进程内单调预算，不比较两台机器的绝对时间。
 * 起点必须在受理调用前捕获，因此数据库锁等待、受理及提交往返均保守计入预算。
 * 不替代数据库原deadline、额度预留或最终提交CAS。
 */
final class AssistantExecutionBudget implements AssistantExecutionPort.Budget {
    private final long started;
    private final long limit;
    private final LongSupplier ticker;

    AssistantExecutionBudget(AssistantTurnRequest request, long beforeAdmission, LongSupplier ticker) {
        Objects.requireNonNull(request, "request");
        if (request.state() != AssistantTurnRequest.State.PROCESSING) {
            throw new IllegalArgumentException("INVALID_EXECUTION_BUDGET_STATE");
        }
        Duration duration = Duration.between(request.createdAt(), request.deadline());
        if (duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofSeconds(8)) > 0) {
            throw new IllegalArgumentException("INVALID_EXECUTION_BUDGET");
        }
        this.started = beforeAdmission;
        this.limit = duration.toNanos();
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    @Override public Duration remaining() {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("ASSISTANT_EXECUTION_CANCELLED");
        }
        long elapsed = ticker.getAsLong() - started;
        if (elapsed < 0 || elapsed >= limit || limit - elapsed < 1_000_000L) {
            throw new AssistantExecutionPort.Expired();
        }
        return Duration.ofNanos(limit - elapsed);
    }
}
