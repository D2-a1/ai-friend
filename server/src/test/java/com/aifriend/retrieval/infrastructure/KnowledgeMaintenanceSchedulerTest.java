package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.aifriend.retrieval.application.*;

class KnowledgeMaintenanceSchedulerTest {
    final Runnable history=mock(Runnable.class), rebuild=mock(Runnable.class), quota=mock(Runnable.class), sessions=mock(Runnable.class);
    final ScheduledExecutorService executor=mock(ScheduledExecutorService.class);
    final KnowledgeMaintenanceScheduler scheduler=new KnowledgeMaintenanceScheduler(history,rebuild,quota,sessions,Duration.ofSeconds(30),executor);
    @Test void constructionDoesNothingAndStartSchedulesOnceWithDelay() {
        verifyNoInteractions(history,rebuild,quota,sessions,executor);
        scheduler.start();scheduler.start();
        verify(executor).scheduleWithFixedDelay(any(Runnable.class),eq(30000L),eq(30000L),eq(TimeUnit.MILLISECONDS));
        verifyNoInteractions(history,rebuild,quota,sessions);
    }
    @Test void everyStageRunsOnceInOrder() {
        scheduler.scan(); var order=inOrder(history,rebuild,quota,sessions);
        order.verify(history).run();order.verify(rebuild).run();order.verify(quota).run();order.verify(sessions).run();
        verifyNoMoreInteractions(history,rebuild,quota,sessions);
    }
    @Test void stageFailureDoesNotStarveOtherMaintenanceOrRetryImmediately() {
        doThrow(new IllegalStateException("PRIVATE_SQL")).when(history).run();
        scheduler.scan(); verify(history).run();verify(rebuild).run();verify(quota).run();verify(sessions).run();
    }
    @Test void closeDuringStagePreventsNextStageAndLateCallbacks() {
        doAnswer(c->{scheduler.close();return null;}).when(history).run();
        scheduler.scan();scheduler.scan();scheduler.close();
        verify(history).run(); verifyNoInteractions(rebuild,quota,sessions);verify(executor).shutdownNow();
        assertThatThrownBy(scheduler::start).isInstanceOf(IllegalStateException.class);
    }
    @Test void cancellationAndInterruptStopRemainingStages() {
        doThrow(new CancellationException()).when(history).run();scheduler.scan();
        verifyNoInteractions(rebuild,quota,sessions);
        reset(history);
        doAnswer(c->{Thread.currentThread().interrupt();return null;}).when(history).run();
        try { scheduler.scan(); assertThat(Thread.currentThread().isInterrupted()).isTrue(); }
        finally { Thread.interrupted(); }
        verifyNoInteractions(rebuild,quota,sessions);
    }
    @Test void invalidIntervalFailsBeforeScheduling() {
        for(Duration interval: new Duration[]{Duration.ZERO,Duration.ofMillis(999),Duration.ofSeconds(301)}) {
            assertThatThrownBy(()->new KnowledgeMaintenanceScheduler(history,rebuild,quota,sessions,interval,executor))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(executor);
    }
    @Test void expiryFailureDoesNotRetryUntilNextScan() {
        doThrow(new IllegalStateException("PRIVATE_SESSION")).doNothing().when(sessions).run();
        scheduler.scan(); verify(sessions).run();
        scheduler.scan(); verify(sessions,times(2)).run();
        verify(history,times(2)).run();
    }
    @Test void importWorkerDoesNotDuplicateIndependentMaintenance() {
        var worker=mock(KnowledgeImportWorker.class);
        var historyPort=mock(KnowledgeHistoryMaintenancePort.class);
        var rebuildPort=mock(KnowledgeDeletionRebuildPort.class);
        var properties=mock(KnowledgeImportProperties.class);
        when(properties.pollInterval()).thenReturn(Duration.ofSeconds(2));
        try(var importing=new KnowledgeImportConfiguration().knowledgeImportScheduler(worker,historyPort,rebuildPort,properties,true)) {
            ReflectionTestUtils.invokeMethod(importing,"scan");
            verify(worker).tick();verifyNoInteractions(historyPort,rebuildPort);
        }
    }
}
