package com.aifriend.retrieval.infrastructure;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aifriend.retrieval.application.KnowledgeImportWorker;
import com.aifriend.retrieval.application.KnowledgeHistoryMaintenancePort;
import com.aifriend.retrieval.application.KnowledgeDeletionRebuildPort;

/**
 * 私有单线程固定延迟扫描器；不注册为Spring默认TaskScheduler，不接管旧调度。
 * @author codex
 * @since 1.0.0
 */
public final class KnowledgeImportScheduler implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeImportScheduler.class);
    private final KnowledgeImportWorker worker;
    private final KnowledgeHistoryMaintenancePort maintenance;
    private final KnowledgeDeletionRebuildPort deletionRebuild;
    private final ScheduledExecutorService executor;
    private final long intervalMillis;
    private boolean started;
    private volatile boolean closed;

    /**
     * 构造但不立即启动线程，生命周期由条件配置管理。
     * @param worker 单任务编排
     * @param maintenance 有界历史回收
     * @param deletionRebuild 删除派生索引发布
     * @param interval 1到60秒固定延迟
     */
    public KnowledgeImportScheduler(KnowledgeImportWorker worker, KnowledgeHistoryMaintenancePort maintenance,
            KnowledgeDeletionRebuildPort deletionRebuild, Duration interval) {
        this(worker, maintenance, deletionRebuild, interval, executor());
    }
    KnowledgeImportScheduler(KnowledgeImportWorker worker, KnowledgeHistoryMaintenancePort maintenance,
            KnowledgeDeletionRebuildPort deletionRebuild, Duration interval, ScheduledExecutorService executor) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
        this.deletionRebuild=Objects.requireNonNull(deletionRebuild,"deletionRebuild");
        this.executor = Objects.requireNonNull(executor, "executor");
        if (interval == null || interval.compareTo(Duration.ofSeconds(1)) < 0 || interval.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("INVALID_IMPORT_POLL_INTERVAL");
        }
        intervalMillis = interval.toMillis();
    }

    /** 只安排一个周期任务，不重复创建并发队列。 */
    public synchronized void start() {
        if (closed) { throw new IllegalStateException("IMPORT_SCHEDULER_CLOSED"); }
        if (!started) {
            executor.scheduleWithFixedDelay(this::scan, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
            started = true;
        }
    }

    private void scan() {
        if (!canContinue()) { return; }
        try {
            maintenance.sweep();
            if (canContinue()) { deletionRebuild.rebuild(); }
            if (canContinue()) { worker.tick(); }
        }
        catch (CancellationException cancelled) { /* 中断/关闭不补执行。 */ }
        catch (RuntimeException unavailable) {
            // 固定错误码，不输出SQL、原文、任务标识或异常堆栈；原任务仍由持久期限收敛。
            LOG.warn("Knowledge import scan deferred reason=STORAGE_OR_STATE_UNAVAILABLE");
        }
    }

    /** 阶段入口核验停止状态；已进入的短事务仍由自身超时/提交规则收尾，不强制伪造回滚。 */
    private boolean canContinue() {
        return !closed && !Thread.currentThread().isInterrupted();
    }

    /** 只停止本组件持有的线程；在途任务保留持久租约状态，不伪造成功。 */
    @Override public synchronized void close() {
        if (!closed) {
            closed = true;
            executor.shutdownNow();
        }
    }

    private static ScheduledExecutorService executor() {
        var pool = new ScheduledThreadPoolExecutor(1, runnable -> {
            var thread = new Thread(runnable, "knowledge-import-worker");
            thread.setDaemon(true);
            return thread;
        });
        pool.setRemoveOnCancelPolicy(true);
        pool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        pool.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return pool;
    }
}
