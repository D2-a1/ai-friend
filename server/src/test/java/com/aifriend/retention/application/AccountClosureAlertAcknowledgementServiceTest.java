package com.aifriend.retention.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aifriend.shared.security.DigestService;

class AccountClosureAlertAcknowledgementServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-20T15:00:00Z");
    private static final String IDEMPOTENCY_KEY = "closure-ack-key-0001";
    private AccountClosureAlertResponderIdentityPort identityPort;
    private AccountClosureAlertAcknowledgementRepositoryPort repositoryPort;
    private DigestService digestService;

    @BeforeEach
    void setUp() {
        identityPort = mock(AccountClosureAlertResponderIdentityPort.class);
        repositoryPort = mock(AccountClosureAlertAcknowledgementRepositoryPort.class);
        digestService = new DigestService();
    }

    @Test
    void shouldVerifyIdentityOutsideRepositoryAndStoreOnlyDigests() {
        UUID deliveryId = UUID.randomUUID();
        byte[] credential = new byte[] {1, 2, 3};
        byte[] subjectHash = digestService.sha256("operator-subject");
        byte[] contextHash = digestService.sha256("mfa-context");
        AccountClosureAlertResponderIdentity identity = new AccountClosureAlertResponderIdentity(
                AccountClosureAlertAudience.ON_CALL,
                subjectHash,
                contextHash,
                NOW.minusSeconds(5),
                NOW.plusSeconds(60));
        when(identityPort.verify(eq(deliveryId), any())).thenReturn(identity);
        AccountClosureAlertAcknowledgement expected = acknowledgement();
        when(repositoryPort.acknowledge(any())).thenReturn(expected);

        AccountClosureAlertAcknowledgement actual = service().acknowledge(
                new AccountClosureAlertAcknowledgementCommand(
                        deliveryId, IDEMPOTENCY_KEY, credential));

        assertEquals(expected, actual);
        ArgumentCaptor<AccountClosureAlertAcknowledgementWrite> captor =
                ArgumentCaptor.forClass(AccountClosureAlertAcknowledgementWrite.class);
        verify(repositoryPort).acknowledge(captor.capture());
        AccountClosureAlertAcknowledgementWrite write = captor.getValue();
        assertEquals(deliveryId, write.deliveryId());
        assertEquals(AccountClosureAlertAudience.ON_CALL, write.audience());
        assertArrayEquals(subjectHash, write.responderSubjectHash());
        assertArrayEquals(contextHash, write.authenticationContextHash());
        assertArrayEquals(digestService.sha256(IDEMPOTENCY_KEY), write.idempotencyKeyHash());
        assertEquals(32, write.requestHash().length);
        assertEquals(NOW, write.acknowledgedAt());
        assertArrayEquals(new byte[] {1, 2, 3}, credential);
    }

    @Test
    void shouldRejectInvalidInputBeforeCallingIdentityProvider() {
        AccountClosureAlertAcknowledgementCommand command =
                new AccountClosureAlertAcknowledgementCommand(
                        UUID.randomUUID(), "short", new byte[] {1});

        assertThrows(IllegalArgumentException.class, () -> service().acknowledge(command));

        verify(identityPort, never()).verify(any(), any());
        verify(repositoryPort, never()).acknowledge(any());
    }

    @Test
    void shouldRejectExpiredOrOverlongIdentityFactBeforeDatabaseWrite() {
        UUID deliveryId = UUID.randomUUID();
        AccountClosureAlertResponderIdentity expired = new AccountClosureAlertResponderIdentity(
                AccountClosureAlertAudience.PRIVACY_OFFICER,
                new byte[32],
                new byte[32],
                NOW.minusSeconds(301),
                NOW.plusSeconds(1));
        when(identityPort.verify(eq(deliveryId), any())).thenReturn(expired);
        AccountClosureAlertAcknowledgementCommand command =
                new AccountClosureAlertAcknowledgementCommand(
                        deliveryId, IDEMPOTENCY_KEY, new byte[] {1});

        assertThrows(IllegalStateException.class, () -> service().acknowledge(command));

        verify(repositoryPort, never()).acknowledge(any());
    }

    private AccountClosureAlertAcknowledgementService service() {
        return new AccountClosureAlertAcknowledgementService(
                identityPort,
                repositoryPort,
                digestService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AccountClosureAlertAcknowledgement acknowledgement() {
        return new AccountClosureAlertAcknowledgement(
                UUID.randomUUID(),
                AccountClosureAlertAudience.ON_CALL,
                AccountClosureAlertAcknowledgementTiming.TIMELY,
                NOW.plusSeconds(60),
                NOW);
    }
}
