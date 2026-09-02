package com.aifriend.retention.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class ContactUnboundCleanupWorkerTest {

    @Test
    void shouldStopWhenNoMoreReadyEventsExist() {
        ContactUnboundCleanupTransactionService transactionService =
                mock(ContactUnboundCleanupTransactionService.class);
        when(transactionService.processNext())
                .thenReturn(true, true, false);
        ContactUnboundCleanupWorker worker =
                new ContactUnboundCleanupWorker(transactionService);

        assertEquals(2, worker.processReady());
        verify(transactionService,
                org.mockito.Mockito.times(3)).processNext();
    }

    @Test
    void shouldProcessAtMostTenEventsPerRun() {
        ContactUnboundCleanupTransactionService transactionService =
                mock(ContactUnboundCleanupTransactionService.class);
        when(transactionService.processNext()).thenReturn(true);
        ContactUnboundCleanupWorker worker =
                new ContactUnboundCleanupWorker(transactionService);

        assertEquals(10, worker.processReady());
        verify(transactionService,
                org.mockito.Mockito.times(10)).processNext();
    }
}
