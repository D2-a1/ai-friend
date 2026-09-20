package com.aifriend.retrieval.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import com.aifriend.retrieval.application.KnowledgeHistoryMaintenancePort.Cleanup;
import com.aifriend.retrieval.application.KnowledgeHistoryMaintenancePort.State;
import com.aifriend.retrieval.application.KnowledgeCleanupRetryPort.Outcome;

class RetryingKnowledgeHistoryMaintenanceTest {
    final KnowledgeHistoryMaintenancePort delegate = mock(KnowledgeHistoryMaintenancePort.class);
    final KnowledgeCleanupRetryPort retry = mock(KnowledgeCleanupRetryPort.class);
    final RetryingKnowledgeHistoryMaintenance service = new RetryingKnowledgeHistoryMaintenance(delegate, retry);
    final UUID token = new UUID(0, 1);

    @Test void noLeaseDoesNoCleanupAndMakesNoCompletionClaim() {
        when(retry.acquire()).thenReturn(Optional.empty());
        assertThat(service.sweep().state()).isEqualTo(State.DEFERRED);
        verifyNoInteractions(delegate); verify(retry, never()).finish(any(), any());
    }

    @Test void everyExplicitOutcomeIsPreservedAndOneBatchHasOneCompletion() {
        for (State state : State.values()) {
            reset(retry, delegate);
            when(retry.acquire()).thenReturn(Optional.of(token));
            when(retry.finish(eq(token), any())).thenReturn(true);
            when(delegate.sweep()).thenReturn(new Cleanup(0, 0, state));
            assertThat(service.sweep().state()).isEqualTo(state);
            var order = inOrder(retry, delegate);
            order.verify(retry).acquire(); order.verify(delegate).sweep();
            order.verify(retry).finish(token, Outcome.valueOf(state.name()));
            verifyNoMoreInteractions(retry, delegate);
        }
    }

    @Test void actualFailureIsPersistedOnceAndExceptionDetailsAreNotPropagated() {
        when(retry.acquire()).thenReturn(Optional.of(token));
        when(delegate.sweep()).thenThrow(new IllegalStateException("PRIVATE_SENTINEL_DATABASE_DETAILS"));
        assertThatThrownBy(service::sweep).hasMessage("KNOWLEDGE_CLEANUP_BATCH_FAILED").hasNoCause();
        verify(retry).finish(token, Outcome.FAILED); verify(delegate).sweep();
    }

    @Test void commitUnknownInBookkeepingDoesNotRerunCleanupOrReportAnotherFailure() {
        when(retry.acquire()).thenReturn(Optional.of(token));
        when(delegate.sweep()).thenReturn(new Cleanup(0, 0, State.DRAINED));
        when(retry.finish(token, Outcome.DRAINED)).thenThrow(new IllegalStateException("COMMIT_UNKNOWN"));
        assertThatThrownBy(service::sweep).hasMessage("COMMIT_UNKNOWN");
        verify(delegate).sweep(); verify(retry, never()).finish(token, Outcome.FAILED);
    }

    @Test void leaseLostAfterCleanupCannotBeReportedAsSuccess() {
        when(retry.acquire()).thenReturn(Optional.of(token));
        when(delegate.sweep()).thenReturn(new Cleanup(1, 1));
        when(retry.finish(token, Outcome.PROGRESSED)).thenReturn(false);
        assertThatThrownBy(service::sweep).hasMessage("KNOWLEDGE_CLEANUP_LEASE_LOST");
        verify(retry, never()).finish(token, Outcome.FAILED);
    }

    @Test void cancellationKeepsLeaseForDurableRecoveryWithoutFalseSuccessOrImmediateRetry() {
        when(retry.acquire()).thenReturn(Optional.of(token));
        when(delegate.sweep()).thenThrow(new CancellationException("CANCELLED"));
        assertThatThrownBy(service::sweep).isInstanceOf(CancellationException.class);
        verify(retry, never()).finish(any(), any()); verify(delegate).sweep();
    }

    @Test void interruptAfterLeaseDoesNotStartCleanupOrClearInterrupt() {
        when(retry.acquire()).thenAnswer(call -> { Thread.currentThread().interrupt(); return Optional.of(token); });
        try {
            assertThatThrownBy(service::sweep).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(delegate); verify(retry, never()).finish(any(), any());
        } finally { Thread.interrupted(); }
    }
}
