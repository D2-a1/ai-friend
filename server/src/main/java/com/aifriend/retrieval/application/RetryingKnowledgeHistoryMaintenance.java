package com.aifriend.retrieval.application;

import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * 只装饰现有有界批次，不持有线程、不睡眠、不在一次调用内重试。
 * 租约事务完成后才清理，清理事务完成后才记录结论；两者不共用长事务。
 * @author Codex
 * @since 1.0.0
 */
public final class RetryingKnowledgeHistoryMaintenance implements KnowledgeHistoryMaintenancePort {
    private final KnowledgeHistoryMaintenancePort delegate;
    private final KnowledgeCleanupRetryPort retry;

    /**
     * 创建不持有线程的装饰器。
     * @param delegate 原有范围的有界清理
     * @param retry 持久重试账本
     */
    public RetryingKnowledgeHistoryMaintenance(KnowledgeHistoryMaintenancePort delegate, KnowledgeCleanupRetryPort retry) {
        this.delegate = Objects.requireNonNull(delegate);
        this.retry = Objects.requireNonNull(retry);
    }

    /** {@inheritDoc} */
    @Override public Cleanup sweep() {
        if (Thread.currentThread().isInterrupted()) { throw new CancellationException("CLEANUP_INTERRUPTED"); }
        var token = retry.acquire();
        if (token.isEmpty()) { return new Cleanup(0, 0, State.DEFERRED); }
        // 取消/进程退出保留已受理租约，由后续数据库时钟收敛，不能谎报成功。
        if (Thread.currentThread().isInterrupted()) { throw new CancellationException("CLEANUP_INTERRUPTED"); }
        final Cleanup result;
        try {
            result = Objects.requireNonNull(delegate.sweep());
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException failed) {
            retry.finish(token.get(), KnowledgeCleanupRetryPort.Outcome.FAILED);
            throw new IllegalStateException("KNOWLEDGE_CLEANUP_BATCH_FAILED");
        }
        if (Thread.currentThread().isInterrupted()) { throw new CancellationException("CLEANUP_INTERRUPTED"); }
        var outcome = switch (result.state()) {
            case PROGRESSED -> KnowledgeCleanupRetryPort.Outcome.PROGRESSED;
            case DEFERRED -> KnowledgeCleanupRetryPort.Outcome.DEFERRED;
            case DRAINED -> KnowledgeCleanupRetryPort.Outcome.DRAINED;
        };
        // 放在清理catch之外：账本提交失败/未知时不追加第二次finish或重跑删除。
        if (!retry.finish(token.get(), outcome)) { throw new IllegalStateException("KNOWLEDGE_CLEANUP_LEASE_LOST"); }
        return result;
    }
}
