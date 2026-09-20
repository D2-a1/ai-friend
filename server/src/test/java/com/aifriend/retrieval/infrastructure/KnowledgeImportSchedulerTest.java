package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aifriend.retrieval.application.KnowledgeImportWorker;
import com.aifriend.retrieval.application.KnowledgeHistoryMaintenancePort;
import com.aifriend.retrieval.application.KnowledgeDeletionRebuildPort;

class KnowledgeImportSchedulerTest {
    private final KnowledgeDeletionRebuildPort rebuild=mock(KnowledgeDeletionRebuildPort.class);
    @Test void onePrivateFixedDelayTaskSurvivesControlledFailureAndClosesIdempotently() {
        var worker = mock(KnowledgeImportWorker.class);
        var executor = mock(ScheduledExecutorService.class);
        var maintenance = mock(KnowledgeHistoryMaintenancePort.class);
        var scheduler = new KnowledgeImportScheduler(worker, maintenance, rebuild, Duration.ofSeconds(2), executor);
        scheduler.start();
        scheduler.start();
        var callback = ArgumentCaptor.forClass(Runnable.class);
        verify(executor, times(1)).scheduleWithFixedDelay(callback.capture(), eq(2000L), eq(2000L), eq(TimeUnit.MILLISECONDS));
        when(worker.tick()).thenThrow(new IllegalStateException("simulated private details"));
        assertThatCode(() -> callback.getValue().run()).doesNotThrowAnyException();
        verify(worker, times(1)).tick();
        verify(maintenance, times(1)).sweep();
        var order=inOrder(maintenance,rebuild,worker);
        order.verify(maintenance).sweep();order.verify(rebuild).rebuild();order.verify(worker).tick();
        scheduler.close();
        scheduler.close();
        verify(executor, times(1)).shutdownNow();
        assertThatThrownBy(scheduler::start).hasMessage("IMPORT_SCHEDULER_CLOSED");
    }

    @Test void invalidIntervalCannotCreateRepeatingWork() {
        var worker = mock(KnowledgeImportWorker.class);
        var executor = mock(ScheduledExecutorService.class);
        var maintenance = mock(KnowledgeHistoryMaintenancePort.class);
        assertThatThrownBy(() -> new KnowledgeImportScheduler(worker, maintenance, rebuild, Duration.ofMillis(500), executor))
                .hasMessage("INVALID_IMPORT_POLL_INTERVAL");
        assertThatThrownBy(() -> new KnowledgeImportScheduler(worker, maintenance, rebuild, Duration.ofMinutes(2), executor))
                .hasMessage("INVALID_IMPORT_POLL_INTERVAL");
        verifyNoInteractions(worker, maintenance, executor);
    }

    @Test void cleanupFailureDoesNotStartAnotherImportInTheSameScan() {
        var worker = mock(KnowledgeImportWorker.class);
        var maintenance = mock(KnowledgeHistoryMaintenancePort.class);
        var executor = mock(ScheduledExecutorService.class);
        var scheduler = new KnowledgeImportScheduler(worker, maintenance, rebuild, Duration.ofSeconds(2), executor);
        scheduler.start();
        var callback = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).scheduleWithFixedDelay(callback.capture(), anyLong(), anyLong(), any());
        when(maintenance.sweep()).thenThrow(new IllegalStateException("simulated cleanup failure"));
        assertThatCode(() -> callback.getValue().run()).doesNotThrowAnyException();
        verifyNoInteractions(worker,rebuild);
        scheduler.close();
    }

    @Test void failedRebuildDoesNotContinueImportOrAddAnotherScheduledTask() {
        var worker=mock(KnowledgeImportWorker.class);var maintenance=mock(KnowledgeHistoryMaintenancePort.class);
        var executor=mock(ScheduledExecutorService.class);
        var scheduler=new KnowledgeImportScheduler(worker,maintenance,rebuild,Duration.ofSeconds(2),executor);
        when(rebuild.rebuild()).thenThrow(new IllegalStateException("simulated"));scheduler.start();
        var callback=ArgumentCaptor.forClass(Runnable.class);
        verify(executor).scheduleWithFixedDelay(callback.capture(),anyLong(),anyLong(),any());
        assertThatCode(()->callback.getValue().run()).doesNotThrowAnyException();verifyNoInteractions(worker);
        scheduler.close();
    }

    @Test void stoppedSchedulerRejectsAlreadyDispatchedCallbackBeforeAnyCleanup() {
        var worker=mock(KnowledgeImportWorker.class);var maintenance=mock(KnowledgeHistoryMaintenancePort.class);
        var executor=mock(ScheduledExecutorService.class);
        var scheduler=new KnowledgeImportScheduler(worker,maintenance,rebuild,Duration.ofSeconds(2),executor);
        scheduler.start();var callback=ArgumentCaptor.forClass(Runnable.class);
        verify(executor).scheduleWithFixedDelay(callback.capture(),anyLong(),anyLong(),any());
        scheduler.close();callback.getValue().run();
        verifyNoInteractions(maintenance,rebuild,worker);
    }

    @Test void interruptedCallbackCannotStartCleanupAndKeepsInterruptFlag() {
        var worker=mock(KnowledgeImportWorker.class);var maintenance=mock(KnowledgeHistoryMaintenancePort.class);
        var executor=mock(ScheduledExecutorService.class);
        var scheduler=new KnowledgeImportScheduler(worker,maintenance,rebuild,Duration.ofSeconds(2),executor);
        scheduler.start();var callback=ArgumentCaptor.forClass(Runnable.class);
        verify(executor).scheduleWithFixedDelay(callback.capture(),anyLong(),anyLong(),any());
        try {
            Thread.currentThread().interrupt();callback.getValue().run();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();verifyNoInteractions(maintenance,rebuild,worker);
        } finally { Thread.interrupted();scheduler.close(); }
    }

    @Test void closeDuringCleanupPreventsRebuildAndImportEvenWithoutExecutorInterrupt() {
        var worker=mock(KnowledgeImportWorker.class);var maintenance=mock(KnowledgeHistoryMaintenancePort.class);
        var executor=mock(ScheduledExecutorService.class);
        var scheduler=new KnowledgeImportScheduler(worker,maintenance,rebuild,Duration.ofSeconds(2),executor);
        when(maintenance.sweep()).thenAnswer(call->{scheduler.close();return new KnowledgeHistoryMaintenancePort.Cleanup(0,0);});
        scheduler.start();var callback=ArgumentCaptor.forClass(Runnable.class);
        verify(executor).scheduleWithFixedDelay(callback.capture(),anyLong(),anyLong(),any());
        callback.getValue().run();verify(maintenance,times(1)).sweep();verifyNoInteractions(rebuild,worker);
    }
}
