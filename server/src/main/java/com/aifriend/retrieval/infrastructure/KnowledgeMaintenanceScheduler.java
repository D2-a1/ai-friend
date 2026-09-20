package com.aifriend.retrieval.infrastructure;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;

/** 独立、串行、有界维护；没有模型或外部告警调用。
 * @author Codex
 * @since 1.0.0
 */
public final class KnowledgeMaintenanceScheduler implements AutoCloseable {
    private static final org.slf4j.Logger LOG=LoggerFactory.getLogger(KnowledgeMaintenanceScheduler.class);
    private final List<Runnable> stages;
    private final ScheduledExecutorService executor;
    private final long interval;
    private volatile boolean closed;
    private boolean started;

    /**
     * 创建单线程维护调度器，显式启动后按固定延迟串行扫描。
     * @param history 原有历史回收批次
     * @param rebuild 原有删除派生重建批次
     * @param quota 原有过期配额批次
     * @param sessions 原有到期问答会话批次
     * @param delay 固定延迟，1至300秒
     */
    public KnowledgeMaintenanceScheduler(Runnable history, Runnable rebuild, Runnable quota, Runnable sessions, Duration delay) {
        this(history,rebuild,quota,sessions,delay,pool());
    }
    KnowledgeMaintenanceScheduler(Runnable history, Runnable rebuild, Runnable quota, Runnable sessions, Duration delay, ScheduledExecutorService executor) {
        stages=List.of(history,rebuild,quota,sessions);
        this.executor=java.util.Objects.requireNonNull(executor);
        if(delay==null || delay.compareTo(Duration.ofSeconds(1))<0 || delay.compareTo(Duration.ofSeconds(300))>0) {
            throw new IllegalArgumentException("INVALID_KNOWLEDGE_MAINTENANCE_INTERVAL");
        }
        interval=delay.toMillis();
    }
    /** 安排一个周期任务；不在构造时连接存储。 */
    public synchronized void start() {
        if(closed) throw new IllegalStateException("KNOWLEDGE_MAINTENANCE_CLOSED");
        if(!started) { executor.scheduleWithFixedDelay(this::scan,interval,interval,TimeUnit.MILLISECONDS); started=true; }
    }
    void scan() {
        for(Runnable stage:stages) {
            if(closed || Thread.currentThread().isInterrupted()) return;
            try { stage.run(); }
            catch(CancellationException cancelled) { return; }
            catch(RuntimeException failed) {
                LOG.warn("Knowledge maintenance deferred reason=STORAGE_OR_STATE_UNAVAILABLE");
            }
        }
    }
    /** 停止本组件，不把已经提交的短事务冒充回滚。 */
    @Override public synchronized void close() { if(!closed) { closed=true; executor.shutdownNow(); } }
    private static ScheduledExecutorService pool() {
        var pool=new ScheduledThreadPoolExecutor(1,r->{var t=new Thread(r,"knowledge-maintenance-worker");t.setDaemon(true);return t;});
        pool.setRemoveOnCancelPolicy(true);
        pool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        pool.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return pool;
    }
}
