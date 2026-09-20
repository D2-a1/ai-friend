package com.aifriend.assistant.infrastructure;

import java.time.*;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import com.aifriend.assistant.application.AssistantExecutionPort;
import com.aifriend.assistant.domain.*;

/**
 * Java17有界在线执行器。无CallerRuns、无自动重试；关闭同时取消队列和运行工作。
 * 不创建定时器、不自动注册bean。调用线程不传播隐式安全上下文，owner须显式传递。
 * @author Codex
 * @since 1.0.0
 */
public final class BoundedAssistantExecutor implements AssistantExecutionPort,AutoCloseable {
    private final ThreadPoolExecutor pool;
    private final Clock clock;
    private final Set<Work> active=ConcurrentHashMap.newKeySet();
    /**
     * 构造时不启动线程，不访问外部服务。
     * @param properties 有界资源配置
     * @param clock UTC时钟，单调时间同步限制预算
     */
    public BoundedAssistantExecutor(AssistantExecutionProperties properties,Clock clock) {
        Objects.requireNonNull(properties); this.clock=Objects.requireNonNull(clock);
        BlockingQueue<Runnable> queue=properties.queueCapacity()==0?new SynchronousQueue<>():new ArrayBlockingQueue<>(properties.queueCapacity());
        AtomicInteger sequence=new AtomicInteger();
        pool=new ThreadPoolExecutor(properties.workers(),properties.workers(),0,TimeUnit.MILLISECONDS,queue,task->{
            Thread worker=new Thread(task,"assistant-online-"+sequence.incrementAndGet()); worker.setDaemon(true); return worker;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    /** {@inheritDoc} */
    @Override public void execute(Instant deadline,Consumer<Control> operation) {
        Objects.requireNonNull(operation);
        var gate=new Gate(Objects.requireNonNull(deadline)); gate.check();
        execute(gate,operation);
    }
    /** {@inheritDoc} */
    @Override public void executeBudget(Instant originalDeadline,Budget budget,Consumer<Control> operation) {
        Objects.requireNonNull(originalDeadline); Objects.requireNonNull(operation);
        var gate=new Gate(Objects.requireNonNull(budget)); gate.check();
        execute(gate,operation);
    }
    private void execute(Gate gate,Consumer<Control> operation) {
        var work=new Work(gate,operation); active.add(work);
        try { pool.execute(work); }
        catch(RejectedExecutionException rejected) {
            work.cancel(false); throw new AssistantSessionException(AssistantReason.RESOURCE_LIMIT);
        }
        try {
            while(true) {
                gate.check();
                try {
                    work.get(Math.min(gate.remaining(),TimeUnit.MILLISECONDS.toNanos(50)),TimeUnit.NANOSECONDS);
                    gate.check(); return;
                } catch(TimeoutException waiting) { /* 周期性核对墙钟，仍使用同一个Future，不重启工作。 */ }
            }
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw cancelled();
        } catch(ExecutionException failed) {
            Throwable cause=failed.getCause();
            if(cause instanceof RuntimeException runtime) throw runtime;
            if(cause instanceof Error error) throw error;
            throw new AssistantSessionException(AssistantReason.STORAGE_UNAVAILABLE);
        } finally {
            // 即使工作吞掉中断，持久gate仍禁止之后的阶段检查通过。
            if(!work.isDone()) work.cancel(true);
            pool.remove(work);
        }
    }
    /** 停止接收新工作并取消当前工作；不等待不合作的第三方，不能保证其立即停止计费。 */
    @Override public void close() {
        pool.shutdownNow();
        for(Work work:active) work.cancel(true);
        pool.purge();
    }
    int queuedCount() { return pool.getQueue().size(); }
    private static CancellationException cancelled() { return new CancellationException("ASSISTANT_EXECUTION_CANCELLED"); }
    private final class Gate implements Control {
        private final Instant deadline;
        private final Budget budget;
        private final long started=System.nanoTime();
        private final long initial;
        private final AtomicBoolean stopped=new AtomicBoolean();
        Gate(Instant deadline) {
            this.deadline=deadline;
            this.budget=null;
            Duration value=Duration.between(clock.instant(),deadline);
            if(value.compareTo(Duration.ofSeconds(8))>0) throw new IllegalArgumentException("INVALID_ASSISTANT_EXECUTION_DEADLINE");
            initial=value.isNegative()?0:value.toNanos();
        }
        Gate(Budget budget) {
            this.deadline=null; this.budget=budget;
            Duration value=budget.remaining();
            if(value.isNegative() || value.isZero() || value.compareTo(Duration.ofSeconds(8))>0)
                throw new IllegalArgumentException("INVALID_ASSISTANT_EXECUTION_BUDGET");
            initial=value.toNanos();
        }
        long remaining() {
            if(stopped.get() || Thread.currentThread().isInterrupted()) throw cancelled();
            long elapsed=System.nanoTime()-started;
            Duration wall=budget==null?Duration.between(clock.instant(),deadline):budget.remaining();
            if(elapsed<0 || elapsed>=initial || wall.isNegative() || wall.isZero()) throw new Expired();
            long monotonic=initial-elapsed;
            return wall.compareTo(Duration.ofNanos(monotonic))<0?wall.toNanos():monotonic;
        }
        @Override public void check() { remaining(); }
    }
    private final class Work extends FutureTask<Void> {
        private final Gate gate;
        Work(Gate gate,Consumer<Control> operation) {
            super(()->{ gate.check(); operation.accept(gate); gate.check(); return null; }); this.gate=gate;
        }
        @Override public boolean cancel(boolean interrupt) { gate.stopped.set(true); return super.cancel(interrupt); }
        @Override protected void done() { active.remove(this); }
    }
}
