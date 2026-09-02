package com.aifriend.invitation.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.invitation.domain.ContactInvitation;
import com.aifriend.invitation.domain.InvitationSession;
import com.aifriend.invitation.domain.InvitationSessionStatus;
import com.aifriend.invitation.domain.InvitationStatus;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;

class InvitationOAuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-09T02:00:00Z");
    private static final String SESSION_TOKEN = "s".repeat(43);
    private static final String OAUTH_STATE = "o".repeat(43);
    private static final String OAUTH_CODE = "local_relative.12345678";

    @Test
    void shouldValidateCallbackBeforeExchangingCodeAndCommitVerifiedIdentity() {
        Fixture fixture = fixture();
        WechatInvitationIdentity identity = new WechatInvitationIdentity("relative");
        when(fixture.identityPort.exchangeCode(OAUTH_CODE)).thenReturn(identity);

        fixture.service.complete(SESSION_TOKEN, OAUTH_STATE, OAUTH_CODE);

        verify(fixture.identityPort).exchangeCode(OAUTH_CODE);
        verify(fixture.attemptLimiter).acquire(aryEq(fixture.digestService.sha256(SESSION_TOKEN)));
        verify(fixture.completionService).commit(
                eq(fixture.invitation.id()),
                eq(fixture.session.id()),
                aryEq(fixture.digestService.sha256(SESSION_TOKEN)),
                aryEq(fixture.digestService.sha256(OAUTH_STATE)),
                org.mockito.ArgumentMatchers.same(identity));
    }

    @Test
    void shouldRejectWrongStateBeforeCallingIdentityProvider() {
        Fixture fixture = fixture();

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> fixture.service.complete(
                        SESSION_TOKEN, "x".repeat(43), OAUTH_CODE));

        assertEquals(ErrorCode.INVITATION_UNAVAILABLE, exception.errorCode());
        verify(fixture.identityPort, never()).exchangeCode(org.mockito.ArgumentMatchers.any());
        verify(fixture.completionService, never()).commit(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldRejectRateLimitedCallbackBeforeDatabaseOrWechatExchange() {
        Fixture fixture = fixture();
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.RATE_LIMITED))
                .when(fixture.attemptLimiter).acquire(org.mockito.ArgumentMatchers.any());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> fixture.service.complete(SESSION_TOKEN, OAUTH_STATE, OAUTH_CODE));

        assertEquals(ErrorCode.RATE_LIMITED, exception.errorCode());
        verify(fixture.sessionRepository, never())
                .findBySessionTokenDigest(org.mockito.ArgumentMatchers.any());
        verify(fixture.identityPort, never()).exchangeCode(org.mockito.ArgumentMatchers.any());
    }

    private Fixture fixture() {
        DigestService digestService = new DigestService();
        InvitationSessionRepositoryPort sessionRepository =
                mock(InvitationSessionRepositoryPort.class);
        InvitationOAuthAttemptLimiterPort attemptLimiter =
                mock(InvitationOAuthAttemptLimiterPort.class);
        InvitationRepositoryPort invitationRepository = mock(InvitationRepositoryPort.class);
        WechatInvitationIdentityPort identityPort = mock(WechatInvitationIdentityPort.class);
        InvitationOAuthCompletionService completionService =
                mock(InvitationOAuthCompletionService.class);
        UUID invitationId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        ContactInvitation invitation = new ContactInvitation(
                invitationId,
                ownerUserId,
                digestService.sha256("proof-tombstone"),
                InvitationStatus.PROOF_REDEEMED,
                NOW.plusSeconds(600),
                NOW.minusSeconds(120),
                digestService.sha256("create-idempotency"),
                null,
                0L);
        InvitationSession session = new InvitationSession(
                UUID.randomUUID(),
                invitationId,
                digestService.sha256(SESSION_TOKEN),
                digestService.sha256("csrf-token"),
                digestService.sha256(OAUTH_STATE),
                null,
                InvitationSessionStatus.AWAITING_WECHAT_OAUTH,
                NOW.plusSeconds(600),
                NOW.minusSeconds(60),
                null,
                0L);
        when(sessionRepository.findBySessionTokenDigest(
                aryEq(digestService.sha256(SESSION_TOKEN))))
                .thenReturn(Optional.of(session));
        when(invitationRepository.findById(invitationId))
                .thenReturn(Optional.of(invitation));
        InvitationOAuthService service = new InvitationOAuthService(
                sessionRepository,
                invitationRepository,
                attemptLimiter,
                identityPort,
                completionService,
                digestService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(
                attemptLimiter,
                sessionRepository,
                service,
                identityPort,
                completionService,
                digestService,
                invitation,
                session);
    }

    private record Fixture(
            InvitationOAuthAttemptLimiterPort attemptLimiter,
            InvitationSessionRepositoryPort sessionRepository,
            InvitationOAuthService service,
            WechatInvitationIdentityPort identityPort,
            InvitationOAuthCompletionService completionService,
            DigestService digestService,
            ContactInvitation invitation,
            InvitationSession session) {
    }
}
