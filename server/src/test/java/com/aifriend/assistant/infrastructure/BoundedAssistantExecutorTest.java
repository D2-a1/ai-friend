package com.aifriend.assistant.infrastructure;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import com.aifriend.assistant.application.AssistantExecutionPort;

/** 真实Java17线程池+合成任务；不启动Spring/数据库或调用模型。 */
class BoundedAssistantExecutorTest {
    private final Clock clock=Clock.systemUTC();
    private BoundedAssistantExecutor pool(int capacity) { return new BoundedAssistantExecutor(new AssistantExecutionProperties(1,capacity),clock); }
    private Instant deadline() { return clock.instant().plusSeconds(5); }
    private static void await(CountDownLatch latch) {
        try { if(!latch.await(3,TimeUnit.SECONDS)) throw new AssertionError("TEST_LATCH_TIMEOUT"); }
        catch(InterruptedException stop) { Thread.currentThread().interrupt(); throw new CancellationException(); }
    }
    private static void until(java.util.function.BooleanSupplier condition) {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean()) {
            if(System.nanoTime()>end) throw new AssertionError("TEST_CONDITION_TIMEOUT");
            Thread.yield();
        }
    }
    @Test void workRunsOnDedicatedWorkerAndPropagatesFailureWithoutRetry() {
        try(var executor=pool(1)) {
            AtomicInteger calls=new AtomicInteger(); Thread caller=Thread.currentThread();
            var failure=new IllegalStateException("FIXTURE_FAILURE");
            assertThatThrownBy(()->executor.execute(deadline(),control->{
                assertThat(Thread.currentThread()).isNotSameAs(caller);
                assertThat(Thread.currentThread().getName()).startsWith("assistant-online-");
                calls.incrementAndGet(); throw failure;
            })).isSameAs(failure);
            assertThat(calls.get()).isEqualTo(1);
            executor.execute(deadline(),control->control.check());
        }
    }
    @Test void fullQueueRejectsWithoutCallerRunsAndCancelledQueueSlotIsReleased() throws Exception {
        var callers=Executors.newFixedThreadPool(2); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var executor=pool(1)) {
            var first=callers.submit(()->executor.execute(deadline(),control->{entered.countDown();await(release);}));
            await(entered); AtomicInteger queuedCalls=new AtomicInteger();
            var queued=callers.submit(()->executor.execute(deadline(),control->queuedCalls.incrementAndGet()));
            until(()->executor.queuedCount()==1);
            assertThatThrownBy(()->executor.execute(deadline(),control->{throw new AssertionError("CALLER_RUNS");})).hasMessage("RESOURCE_LIMIT");
            queued.cancel(true); until(()->executor.queuedCount()==0);
            release.countDown(); first.get(2,TimeUnit.SECONDS);
            assertThat(queuedCalls.get()).isZero();
        } finally { release.countDown();callers.shutdownNow(); }
    }
    @Test void sharedBudgetExpiresInQueueWithoutStartingWorkDespiteUnrelatedDatabaseInstant() throws Exception {
        var callers=Executors.newSingleThreadExecutor(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var executor=pool(1)) {
            var first=callers.submit(()->executor.execute(deadline(),control->{entered.countDown();await(release);}));
            await(entered); AtomicInteger calls=new AtomicInteger();
            long started=System.nanoTime();
            AssistantExecutionPort.Budget budget=()->{
                long left=TimeUnit.MILLISECONDS.toNanos(120)-(System.nanoTime()-started);
                if(left<=0) throw new AssistantExecutionPort.Expired();
                return Duration.ofNanos(left);
            };
            assertThatThrownBy(()->executor.executeBudget(Instant.EPOCH,budget,control->calls.incrementAndGet()))
                    .isInstanceOf(AssistantExecutionPort.Expired.class);
            assertThat(executor.queuedCount()).isZero();
            assertThat(calls.get()).isZero();
            release.countDown(); first.get(2,TimeUnit.SECONDS);
        } finally { release.countDown();callers.shutdownNow(); }
    }
    @Test void queuedDeadlineExpiresWithoutStartingTaskOrResettingBudget() throws Exception {
        var callers=Executors.newSingleThreadExecutor(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var executor=pool(1)) {
            var first=callers.submit(()->executor.execute(deadline(),control->{entered.countDown();await(release);}));
            await(entered); AtomicInteger calls=new AtomicInteger();
            assertThatThrownBy(()->executor.execute(clock.instant().plusMillis(120),control->calls.incrementAndGet()))
                    .isInstanceOf(AssistantExecutionPort.Expired.class);
            assertThat(executor.queuedCount()).isZero(); release.countDown(); first.get(2,TimeUnit.SECONDS);
            assertThat(calls.get()).isZero();
        } finally { release.countDown();callers.shutdownNow(); }
    }
    @Test void swallowedInterruptCannotPassPersistentCancellationGate() throws Exception {
        var callers=Executors.newSingleThreadExecutor(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var checked=new CountDownLatch(1); var interrupted=new CountDownLatch(1); AtomicBoolean blocked=new AtomicBoolean();
        try(var executor=pool(1)) {
            var caller=callers.submit(()->executor.execute(deadline(),control->{
                entered.countDown();
                boolean waiting=true;
                while(waiting) try { waiting=!release.await(3,TimeUnit.SECONDS); }
                    catch(InterruptedException deliberatelySwallowed) { interrupted.countDown(); }
                try { control.check(); } catch(CancellationException expected) { blocked.set(true); }
                finally { checked.countDown(); }
            }));
            await(entered); caller.cancel(true);
            // 等待调用侧取消已传播至工作线程，而非依赖固定sleep。
            await(interrupted); release.countDown(); await(checked);
            assertThat(blocked.get()).isTrue();
        } finally { release.countDown();callers.shutdownNow(); }
    }
    @Test void closeCancelsQueuedAndRunningWorkAndRejectsNewWork() throws Exception {
        var callers=Executors.newFixedThreadPool(2); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var executor=pool(1)) {
            var first=callers.submit(()->executor.execute(deadline(),control->{entered.countDown();await(release);}));
            await(entered);
            var second=callers.submit(()->executor.execute(deadline(),control->{throw new AssertionError("QUEUED_AFTER_CLOSE");}));
            until(()->executor.queuedCount()==1); executor.close();
            assertThatThrownBy(()->first.get(2,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            assertThatThrownBy(()->second.get(2,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            assertThatThrownBy(()->executor.execute(deadline(),control->{})).hasMessage("RESOURCE_LIMIT");
            assertThat(executor.queuedCount()).isZero();
        } finally { release.countDown();callers.shutdownNow(); }
    }
    @Test void zeroCapacityCannotQueueAndInvalidConfigurationOrDeadlineFailsEarly() throws Exception {
        for(int workers:new int[]{0,17}) assertThatIllegalArgumentException().isThrownBy(()->new AssistantExecutionProperties(workers,1));
        for(int capacity:new int[]{-1,129}) assertThatIllegalArgumentException().isThrownBy(()->new AssistantExecutionProperties(1,capacity));
        try(var executor=pool(0)) {
            assertThatThrownBy(()->executor.execute(clock.instant().minusMillis(1),control->{throw new AssertionError();}))
                    .isInstanceOf(AssistantExecutionPort.Expired.class);
            assertThatIllegalArgumentException().isThrownBy(()->executor.execute(clock.instant().plusSeconds(9),control->{}));
            executor.execute(deadline(),control->control.check());
            assertThat(executor.queuedCount()).isZero();
        }
    }
    @Test void preInterruptedCallerDoesNotRunWorkAndKeepsInterrupt() {
        try(var executor=pool(1)) {
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(()->executor.execute(deadline(),control->{throw new AssertionError();})).isInstanceOf(CancellationException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally { Thread.interrupted(); }
        }
    }
    @Test void zeroQueueRejectsWhenOnlyWorkerIsOccupied() throws Exception {
        var callers=Executors.newSingleThreadExecutor(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var executor=pool(0)) {
            var first=callers.submit(()->executor.execute(deadline(),control->{entered.countDown();await(release);}));
            await(entered);
            assertThatThrownBy(()->executor.execute(deadline(),control->{throw new AssertionError("UNBOUNDED_FALLBACK");})).hasMessage("RESOURCE_LIMIT");
            assertThat(executor.queuedCount()).isZero(); release.countDown(); first.get(2,TimeUnit.SECONDS);
        } finally { release.countDown();callers.shutdownNow(); }
    }
}
