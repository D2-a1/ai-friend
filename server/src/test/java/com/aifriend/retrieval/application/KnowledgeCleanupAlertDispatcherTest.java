package com.aifriend.retrieval.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import com.aifriend.retrieval.application.KnowledgeCleanupAlertDeliveryPort.Event;
import com.aifriend.retrieval.application.KnowledgeCleanupRetryPort.AlertLevel;
import com.aifriend.retrieval.application.KnowledgeCleanupRetryPort.Outcome;

class KnowledgeCleanupAlertDispatcherTest {
    final KnowledgeCleanupRetryPort ledger = mock(KnowledgeCleanupRetryPort.class);
    final KnowledgeCleanupAlertDeliveryPort delivery = mock(KnowledgeCleanupAlertDeliveryPort.class);
    final KnowledgeCleanupAlertDispatcher dispatcher = new KnowledgeCleanupAlertDispatcher(ledger, delivery);
    final Instant start = Instant.parse("2026-09-12T00:00:00Z");

    KnowledgeCleanupRetryState backlog() {
        return new KnowledgeCleanupRetryState(0, null, start, start, null, null, false, 5, 2, 4, 1);
    }

    @Test void noPendingDoesNotCallTransportOrAcknowledge() {
        when(ledger.status()).thenReturn(KnowledgeCleanupRetryState.initial(start));
        assertThat(dispatcher.dispatch()).isZero();
        verifyNoInteractions(delivery); verify(ledger).status(); verifyNoMoreInteractions(ledger);
    }

    @Test void oldestOnlyPerCategoryBoundedAndStrictlyOutsideLedgerCalls() {
        when(ledger.status()).thenReturn(backlog());
        when(delivery.deliverAndVerify(any())).thenAnswer(c -> Optional.of(c.getArgument(0)));
        when(ledger.acknowledge(any(), anyLong())).thenReturn(true);
        assertThat(dispatcher.dispatch()).isEqualTo(2);
        var order = inOrder(ledger, delivery);
        order.verify(ledger).status();
        order.verify(delivery).deliverAndVerify(new Event(AlertLevel.FIRST_FAILURE, 3));
        order.verify(ledger).acknowledge(AlertLevel.FIRST_FAILURE, 3);
        order.verify(delivery).deliverAndVerify(new Event(AlertLevel.OVERDUE, 2));
        order.verify(ledger).acknowledge(AlertLevel.OVERDUE, 2);
        verifyNoMoreInteractions(ledger, delivery);
    }

    @Test void acceptedNullAndWrongReceiptsNeverAcknowledge() {
        when(ledger.status()).thenReturn(backlog());
        when(delivery.deliverAndVerify(any())).thenReturn(Optional.empty());
        assertThat(dispatcher.dispatch()).isZero();
        when(delivery.deliverAndVerify(any())).thenReturn(null);
        assertThat(dispatcher.dispatch()).isZero();
        when(delivery.deliverAndVerify(any())).thenReturn(Optional.of(new Event(AlertLevel.OVERDUE, 4)));
        assertThat(dispatcher.dispatch()).isZero();
        verify(ledger, never()).acknowledge(any(), anyLong());
    }

    @Test void transportFailureDoesNotSuppressOverdueAndDoesNotLeakException() {
        when(ledger.status()).thenReturn(backlog());
        when(delivery.deliverAndVerify(new Event(AlertLevel.FIRST_FAILURE, 3)))
                .thenThrow(new IllegalStateException("PRIVATE_TRANSPORT_SECRET"));
        when(delivery.deliverAndVerify(new Event(AlertLevel.OVERDUE, 2)))
                .thenReturn(Optional.of(new Event(AlertLevel.OVERDUE, 2)));
        when(ledger.acknowledge(AlertLevel.OVERDUE, 2)).thenReturn(true);
        assertThat(dispatcher.dispatch()).isEqualTo(1);
        verify(ledger, never()).acknowledge(AlertLevel.FIRST_FAILURE, 3);
    }

    @Test void unknownAcknowledgementCommitStopsWithoutRetryOrSecondDelivery() {
        when(ledger.status()).thenReturn(backlog());
        when(delivery.deliverAndVerify(any())).thenAnswer(c -> Optional.of(c.getArgument(0)));
        when(ledger.acknowledge(any(), anyLong())).thenThrow(new IllegalStateException("PRIVATE_SQL"));
        assertThatThrownBy(dispatcher::dispatch).hasMessage("KNOWLEDGE_CLEANUP_ALERT_LEDGER_UNAVAILABLE").hasNoCause();
        verify(delivery, times(1)).deliverAndVerify(any()); verify(ledger, times(1)).acknowledge(any(), anyLong());
    }

    @Test void staleConcurrentAcknowledgementIsNotReportedAsNewSuccess() {
        when(ledger.status()).thenReturn(backlog());
        when(delivery.deliverAndVerify(any())).thenAnswer(c -> Optional.of(c.getArgument(0)));
        when(ledger.acknowledge(any(), anyLong())).thenReturn(false);
        assertThat(dispatcher.dispatch()).isZero();
        verify(delivery, times(2)).deliverAndVerify(any());
    }

    @Test void cancellationAndInterruptDoNotAcknowledgeOrClearInterrupt() {
        when(ledger.status()).thenReturn(backlog());
        when(delivery.deliverAndVerify(any())).thenThrow(new CancellationException());
        assertThatThrownBy(dispatcher::dispatch).isInstanceOf(CancellationException.class);
        doAnswer(c -> { Thread.currentThread().interrupt(); return Optional.of(c.getArgument(0)); })
                .when(delivery).deliverAndVerify(any());
        try {
            assertThatThrownBy(dispatcher::dispatch).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        verify(ledger, never()).acknowledge(any(), anyLong());
    }

    @Test void databaseFailureNeverStartsDelivery() {
        when(ledger.status()).thenThrow(new IllegalStateException("PRIVATE_SQL"));
        assertThatThrownBy(dispatcher::dispatch).hasMessage("KNOWLEDGE_CLEANUP_ALERT_LEDGER_UNAVAILABLE").hasNoCause();
        verifyNoInteractions(delivery);
    }

    @Test void realStateOutagePastFifteenMinutesDrainRestartAndRecoveryKeepBothEvents() {
        UUID token = new UUID(0, 1);
        KnowledgeCleanupRetryState[] state = { KnowledgeCleanupRetryState.initial(start).acquire(token).finish(token, Outcome.FAILED) };
        when(ledger.status()).thenAnswer(c -> state[0]);
        when(ledger.acknowledge(any(), anyLong())).thenAnswer(c -> {
            var next = state[0].acknowledge(c.getArgument(0), c.getArgument(1));
            boolean changed = !next.equals(state[0]); state[0] = next; return changed;
        });
        when(delivery.deliverAndVerify(any())).thenReturn(Optional.empty());
        assertThat(dispatcher.dispatch()).isZero();
        state[0] = state[0].observe(start.plusSeconds(901));
        assertThat(dispatcher.dispatch()).isZero();
        assertThat(state[0].escalations()).isEqualTo(1);
        state[0] = state[0].acquire(token).finish(token, Outcome.DRAINED);
        assertThat(state[0].failures()).isZero();
        assertThat(state[0].firstAcknowledged()).isZero();
        when(delivery.deliverAndVerify(any())).thenAnswer(c -> Optional.of(c.getArgument(0)));
        assertThat(new KnowledgeCleanupAlertDispatcher(ledger, delivery).dispatch()).isEqualTo(2);
        assertThat(state[0].firstAcknowledged()).isEqualTo(1);
        assertThat(state[0].escalationAcknowledged()).isEqualTo(1);
        assertThat(dispatcher.dispatch()).isZero();
    }
}
