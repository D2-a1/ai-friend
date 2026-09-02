package com.aifriend.invitation.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.invitation.domain.ContactInvitation;
import com.aifriend.invitation.domain.InvitationSession;
import com.aifriend.invitation.domain.InvitationSessionStatus;
import com.aifriend.invitation.domain.InvitationStatus;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.SecurityKeyMaterial;
import com.aifriend.shared.security.SensitiveDataProtector;

class InvitationOAuthCompletionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-09T02:30:00Z");

    @Test
    void shouldConsumeStateAndPersistOnlyProtectedIdentity() {
        DigestService digestService = new DigestService();
        SensitiveDataProtector protector = protector();
        InvitationRepositoryPort invitations = mock(InvitationRepositoryPort.class);
        InvitationSessionRepositoryPort sessions = mock(InvitationSessionRepositoryPort.class);
        AuditEventPort audit = mock(AuditEventPort.class);
        UUID invitationId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        byte[] sessionDigest = digestService.sha256("session-token");
        byte[] stateDigest = digestService.sha256("oauth-state");
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
                sessionId,
                invitationId,
                sessionDigest,
                digestService.sha256("csrf-token"),
                stateDigest,
                null,
                InvitationSessionStatus.AWAITING_WECHAT_OAUTH,
                NOW.plusSeconds(600),
                NOW.minusSeconds(60),
                null,
                0L);
        when(invitations.findByIdForUpdate(invitationId))
                .thenReturn(Optional.of(invitation));
        when(sessions.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        InvitationOAuthCompletionService service = new InvitationOAuthCompletionService(
                invitations,
                sessions,
                protector,
                digestService,
                audit,
                Clock.fixed(NOW, ZoneOffset.UTC));

        service.commit(
                invitationId,
                sessionId,
                sessionDigest,
                stateDigest,
                new WechatInvitationIdentity("relative-subject"));

        ArgumentCaptor<ContactInvitation> invitationCaptor =
                ArgumentCaptor.forClass(ContactInvitation.class);
        verify(invitations).save(invitationCaptor.capture());
        assertEquals(InvitationStatus.WECHAT_VERIFIED,
                invitationCaptor.getValue().status());
        ArgumentCaptor<InvitationSession> sessionCaptor =
                ArgumentCaptor.forClass(InvitationSession.class);
        verify(sessions).save(sessionCaptor.capture());
        InvitationSession verified = sessionCaptor.getValue();
        assertEquals(InvitationSessionStatus.WECHAT_VERIFIED, verified.status());
        assertEquals(NOW, verified.oauthVerifiedAt());
        assertArrayEquals(
                protector.subjectHmac("relative-subject"), verified.oauthSubjectHash());
        assertEquals("relative-subject", protector.decrypt(verified.oauthSubjectCipher()));
        assertFalse(Arrays.equals(stateDigest, verified.oauthStateDigest()));
        verify(audit).append(
                ownerUserId, "INVITATION_WECHAT_VERIFY", "SUCCESS", null, NOW);
    }

    private SensitiveDataProtector protector() {
        byte[] keyBytes = new byte[32];
        for (int index = 0; index < keyBytes.length; index++) {
            keyBytes[index] = (byte) (index + 1);
        }
        SecurityKeyMaterial keyMaterial = new SecurityKeyMaterial(
                new SecretKeySpec(keyBytes, "HmacSHA256"),
                new SecretKeySpec(keyBytes, "AES"),
                new SecretKeySpec(keyBytes, "HmacSHA256"));
        return new SensitiveDataProtector(keyMaterial);
    }
}
