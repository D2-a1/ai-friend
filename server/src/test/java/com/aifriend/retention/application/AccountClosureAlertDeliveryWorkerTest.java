package com.aifriend.retention.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.logging.TestLogCapture;

class AccountClosureAlertDeliveryWorkerTest {

    private static final Instant NOW = Instant.parse("2026-08-20T14:00:00Z");
    private static final String PROVIDER_REFERENCE =
            "1234567890abcdef1234567890abcdef";
    private static final String SENSITIVE_FAILURE_DETAIL = "provider-secret-detail";
    private AccountClosureAlertDeliveryRepositoryPort repositoryPort;
    private AccountClosureAlertDeliveryPort deliveryPort;
    private DigestService digestService;

    @BeforeEach
    void setUp() {
        repositoryPort = mock(AccountClosureAlertDeliveryRepositoryPort.class);
        deliveryPort = mock(AccountClosureAlertDeliveryPort.class);
        digestService = new DigestService();
    }

    @Test
    void shouldPersistSubmissionBeforeAnyDeliveryConfirmation() {
        AccountClosureAlertDelivery delivery = pendingDelivery(0);
        when(repositoryPort.listReady(NOW, 50)).thenReturn(List.of(delivery));
        when(deliveryPort.submit(delivery))
                .thenReturn(new AccountClosureAlertSubmission(PROVIDER_REFERENCE));

        assertEquals(0, worker().processReady());

        verify(repositoryPort).materializePending(NOW, 50);
        verify(repositoryPort).confirmSubmitted(
                delivery.deliveryId(),
                PROVIDER_REFERENCE,
                NOW,
                NOW.plusSeconds(30));
        verify(repositoryPort, never()).confirmDelivered(any(), any(), any());
    }

    @Test
    void shouldKeepSubmittedReferenceWhileProviderIsPending() {
        AccountClosureAlertDelivery delivery = submittedDelivery(0);
        when(repositoryPort.listReady(NOW, 50)).thenReturn(List.of(delivery));
        when(deliveryPort.verify(delivery)).thenReturn(
                new AccountClosureAlertDeliveryVerification(
                        AccountClosureAlertVerificationStatus.PENDING, null));

        assertEquals(0, worker().processReady());

        verify(repositoryPort).scheduleVerification(
                delivery.deliveryId(), NOW, NOW.plusSeconds(30));
        verify(repositoryPort, never()).resetFailedSubmission(any(), any(), any());
    }

    @Test
    void shouldConfirmOnlyProviderDeliveredStatusWithStableReceiptHash() {
        AccountClosureAlertDelivery delivery = submittedDelivery(0);
        byte[] proof = "pushplus-app-delivered:proof".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(repositoryPort.listReady(NOW, 50)).thenReturn(List.of(delivery));
        when(deliveryPort.verify(delivery)).thenReturn(
                new AccountClosureAlertDeliveryVerification(
                        AccountClosureAlertVerificationStatus.DELIVERED, proof));
        when(repositoryPort.confirmDelivered(
                eq(delivery.deliveryId()), any(), eq(NOW))).thenReturn(true);

        assertEquals(1, worker().processReady());

        verify(repositoryPort).confirmDelivered(
                delivery.deliveryId(), digestService.sha256(proof), NOW);
    }

    @Test
    void shouldResetOnlyAfterProviderExplicitlyReportsFailure() {
        AccountClosureAlertDelivery delivery = submittedDelivery(1);
        when(repositoryPort.listReady(NOW, 50)).thenReturn(List.of(delivery));
        when(deliveryPort.verify(delivery)).thenReturn(
                new AccountClosureAlertDeliveryVerification(
                        AccountClosureAlertVerificationStatus.FAILED, null));

        assertEquals(0, worker().processReady());

        verify(repositoryPort).resetFailedSubmission(
                delivery.deliveryId(), NOW, NOW.plusSeconds(60));
    }

    @Test
    void shouldRetainSubmissionAndCapTransportRetryAtFifteenMinutes() {
        AccountClosureAlertDelivery delivery = submittedDelivery(8);
        when(repositoryPort.listReady(NOW, 50)).thenReturn(List.of(delivery));
        doThrow(new IllegalStateException(SENSITIVE_FAILURE_DETAIL))
                .when(deliveryPort).verify(delivery);

        int delivered;
        List<String> messages;
        try (TestLogCapture capture = TestLogCapture.forClass(
                AccountClosureAlertDeliveryWorker.class)) {
            delivered = worker().processReady();
            messages = capture.messages();
        }

        assertEquals(0, delivered);

        verify(repositoryPort).markRetry(
                delivery.deliveryId(), NOW, NOW.plusSeconds(900));
        verify(repositoryPort, never()).resetFailedSubmission(any(), any(), any());
        assertEquals(1, messages.size());
        String message = messages.get(0);
        assertTrue(message.contains("stage=ACCOUNT_CLOSURE_ALERT_DELIVERY"));
        assertTrue(message.contains("errorType=IllegalStateException"));
        assertTrue(message.contains("retryCount=8"));
        assertTrue(message.contains("nextAttemptAt=2026-08-20T14:15:00Z"));
        assertFalse(message.contains(SENSITIVE_FAILURE_DETAIL));
        assertFalse(message.contains(delivery.deliveryId().toString()));
        assertFalse(message.contains(PROVIDER_REFERENCE));
    }

    private AccountClosureAlertDeliveryWorker worker() {
        return new AccountClosureAlertDeliveryWorker(
                repositoryPort,
                deliveryPort,
                digestService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AccountClosureAlertDelivery pendingDelivery(int retryCount) {
        return new AccountClosureAlertDelivery(
                UUID.randomUUID(),
                AccountClosureAlertAudience.ON_CALL,
                AccountClosureAlertType.ACCOUNT_CLOSURE_P0_OPENED,
                NOW.minusSeconds(60),
                NOW.plusSeconds(15 * 60),
                retryCount);
    }

    private AccountClosureAlertDelivery submittedDelivery(int retryCount) {
        AccountClosureAlertDelivery pending = pendingDelivery(retryCount);
        return new AccountClosureAlertDelivery(
                pending.deliveryId(),
                pending.audience(),
                pending.type(),
                pending.occurredAt(),
                pending.acknowledgementDueAt(),
                pending.retryCount(),
                PROVIDER_REFERENCE);
    }
}
