package com.aifriend.invitation.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aifriend.contact.application.ContactBindingRepositoryPort;
import com.aifriend.contact.application.WechatLocatorPolicy;
import com.aifriend.contact.domain.ContactBinding;
import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.invitation.domain.ContactInvitation;
import com.aifriend.invitation.domain.InvitationSession;
import com.aifriend.invitation.domain.InvitationSessionStatus;
import com.aifriend.invitation.domain.InvitationStatus;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.SecurityKeyMaterial;
import com.aifriend.shared.security.SensitiveDataProtector;

class InvitationAcceptanceServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-09T03:00:00Z");
    private static final String SESSION_TOKEN = "s".repeat(43);
    private static final String CSRF_TOKEN = "c".repeat(43);
    private static final String ROTATED_CSRF_TOKEN = "r".repeat(43);
    private static final String IDEMPOTENCY_KEY = "01JINVITATIONACCEPT0000000001";
    private static final String POLICY_VERSION = "invitation-consent-v1";
    private static final String WECHAT_ID = "relative_123";

    @Test
    void shouldExposeOnlyMinimalVerifiedSessionWithoutInventingInviterName() {
        Fixture fixture = fixture(InvitationStatus.WECHAT_VERIFIED,
                InvitationSessionStatus.WECHAT_VERIFIED);

        InvitationSessionView view = fixture.service.view(SESSION_TOKEN);

        assertNull(view.inviterDisplayName());
        assertEquals("将你添加为已绑定亲友", view.relationshipSummary());
        assertEquals(POLICY_VERSION, view.consentPolicyVersion());
        assertEquals(NOW.plusSeconds(600), view.expiresAt());
        assertTrue(view.readyForConsent());
        assertEquals(ROTATED_CSRF_TOKEN, view.csrfToken());
        ArgumentCaptor<InvitationSession> sessionCaptor =
                ArgumentCaptor.forClass(InvitationSession.class);
        verify(fixture.sessions).save(sessionCaptor.capture());
        InvitationSession rotatedSession = sessionCaptor.getValue();
        assertArrayEquals(
                fixture.digestService.sha256(ROTATED_CSRF_TOKEN),
                rotatedSession.csrfTokenDigest());
        when(fixture.sessions.findBySessionTokenDigest(
                aryEq(fixture.digestService.sha256(SESSION_TOKEN))))
                .thenReturn(Optional.of(rotatedSession));
        BusinessException replay = assertThrows(
                BusinessException.class,
                () -> fixture.service.accept(
                        SESSION_TOKEN,
                        CSRF_TOKEN,
                        IDEMPOTENCY_KEY,
                        true,
                        POLICY_VERSION,
                        WECHAT_ID));
        assertEquals(ErrorCode.INVITATION_UNAVAILABLE, replay.errorCode());
    }

    @Test
    void shouldAcceptAtomicallyCreateAliasReadyBindingAndRemoveSessionIdentity() {
        Fixture fixture = fixture(InvitationStatus.WECHAT_VERIFIED,
                InvitationSessionStatus.WECHAT_VERIFIED);
        when(fixture.contacts.countOccupying(fixture.ownerUserId)).thenReturn(0L);
        when(fixture.invitations.countPending(fixture.ownerUserId)).thenReturn(1L);
        when(fixture.contacts.findByOwnerAndSubjectForUpdate(
                eq(fixture.ownerUserId),
                aryEq(fixture.session.oauthSubjectHash())))
                .thenReturn(Optional.empty());

        AcceptedInvitation result = fixture.service.accept(
                SESSION_TOKEN, CSRF_TOKEN, IDEMPOTENCY_KEY, true, POLICY_VERSION, WECHAT_ID);

        assertEquals(AcceptedInvitation.ACCEPTED_READY_FOR_ALIAS, result.status());
        ArgumentCaptor<ContactBinding> bindingCaptor =
                ArgumentCaptor.forClass(ContactBinding.class);
        verify(fixture.contacts).save(bindingCaptor.capture());
        ContactBinding binding = bindingCaptor.getValue();
        assertEquals(fixture.ownerUserId, binding.ownerUserId());
        assertArrayEquals(fixture.session.oauthSubjectHash(), binding.contactSubjectHash());
        assertArrayEquals(fixture.session.oauthSubjectCipher(), binding.contactSubjectCipher());
        assertEquals(ContactStatus.ACTIVE_NO_ALIAS, binding.status());
        assertEquals(WECHAT_ID, fixture.protector.decrypt(binding.wechatLocatorCipher()));
        assertArrayEquals(
                fixture.protector.subjectHmac(WechatLocatorPolicy.HMAC_DOMAIN + WECHAT_ID),
                binding.wechatLocatorHash());
        assertEquals(WechatLocatorPolicy.INVITATION_WECHAT_ID_VERSION,
                binding.localVerificationVersion());
        assertEquals(NOW, binding.verifiedAt());
        assertEquals(POLICY_VERSION, binding.consentPolicyVersion());
        assertEquals(NOW, binding.consentedAt());

        ArgumentCaptor<ContactInvitation> invitationCaptor =
                ArgumentCaptor.forClass(ContactInvitation.class);
        verify(fixture.invitations).save(invitationCaptor.capture());
        assertEquals(InvitationStatus.ACCEPTED, invitationCaptor.getValue().status());

        ArgumentCaptor<InvitationSession> sessionCaptor =
                ArgumentCaptor.forClass(InvitationSession.class);
        verify(fixture.sessions).save(sessionCaptor.capture());
        InvitationSession terminated = sessionCaptor.getValue();
        assertEquals(InvitationSessionStatus.TERMINATED, terminated.status());
        assertNull(terminated.oauthSubjectHash());
        assertNull(terminated.oauthSubjectCipher());
        assertArrayEquals(
                fixture.digestService.sha256(IDEMPOTENCY_KEY),
                terminated.acceptIdempotencyKeyDigest());
        verify(fixture.audit).append(
                fixture.ownerUserId, "INVITATION_ACCEPT", "SUCCESS", null, NOW);
    }

    @Test
    void shouldRejectForgedCsrfAndTotalLimitWithoutWritingBinding() {
        Fixture forged = fixture(InvitationStatus.WECHAT_VERIFIED,
                InvitationSessionStatus.WECHAT_VERIFIED);
        BusinessException csrfFailure = assertThrows(
                BusinessException.class,
                () -> forged.service.accept(
                        SESSION_TOKEN,
                        "x".repeat(43),
                        IDEMPOTENCY_KEY,
                        true,
                        POLICY_VERSION,
                        WECHAT_ID));
        assertEquals(ErrorCode.INVITATION_UNAVAILABLE, csrfFailure.errorCode());
        verify(forged.contacts, never()).save(org.mockito.ArgumentMatchers.any());

        Fixture limited = fixture(InvitationStatus.WECHAT_VERIFIED,
                InvitationSessionStatus.WECHAT_VERIFIED);
        when(limited.contacts.countOccupying(limited.ownerUserId)).thenReturn(20L);
        when(limited.invitations.countPending(limited.ownerUserId)).thenReturn(1L);
        BusinessException limitFailure = assertThrows(
                BusinessException.class,
                () -> limited.service.accept(
                        SESSION_TOKEN,
                        CSRF_TOKEN,
                        IDEMPOTENCY_KEY,
                        true,
                        POLICY_VERSION,
                        WECHAT_ID));
        assertEquals(ErrorCode.INVITATION_UNAVAILABLE, limitFailure.errorCode());
        verify(limited.contacts, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldUpgradeExistingPendingBindingWithoutChangingContactIdentity() {
        Fixture fixture = fixture(InvitationStatus.WECHAT_VERIFIED,
                InvitationSessionStatus.WECHAT_VERIFIED);
        ContactBinding existing = new ContactBinding(
                UUID.randomUUID(), fixture.ownerUserId,
                fixture.session.oauthSubjectHash(), fixture.session.oauthSubjectCipher(),
                null, null, null, null, null, "亲友",
                ContactStatus.PENDING_LOCAL_VERIFY, fixture.ownerUserId,
                4L, NOW.minusSeconds(3600), NOW.minusSeconds(1800), null);
        when(fixture.contacts.countOccupying(fixture.ownerUserId)).thenReturn(1L);
        when(fixture.invitations.countPending(fixture.ownerUserId)).thenReturn(1L);
        when(fixture.contacts.findByOwnerAndSubjectForUpdate(
                eq(fixture.ownerUserId), aryEq(fixture.session.oauthSubjectHash())))
                .thenReturn(Optional.of(existing));

        fixture.service.accept(
                SESSION_TOKEN, CSRF_TOKEN, IDEMPOTENCY_KEY, true, POLICY_VERSION, WECHAT_ID);

        ArgumentCaptor<ContactBinding> bindingCaptor =
                ArgumentCaptor.forClass(ContactBinding.class);
        verify(fixture.contacts).save(bindingCaptor.capture());
        ContactBinding upgraded = bindingCaptor.getValue();
        assertEquals(existing.id(), upgraded.id());
        assertEquals(existing.createdAt(), upgraded.createdAt());
        assertEquals(ContactStatus.ACTIVE_NO_ALIAS, upgraded.status());
        assertEquals(WECHAT_ID, fixture.protector.decrypt(upgraded.wechatLocatorCipher()));
    }

    @Test
    void shouldRejectWechatIdAlreadyUsedByAnotherContact() {
        Fixture fixture = fixture(InvitationStatus.WECHAT_VERIFIED,
                InvitationSessionStatus.WECHAT_VERIFIED);
        when(fixture.contacts.countOccupying(fixture.ownerUserId)).thenReturn(0L);
        when(fixture.invitations.countPending(fixture.ownerUserId)).thenReturn(1L);
        when(fixture.contacts.findByOwnerAndSubjectForUpdate(
                eq(fixture.ownerUserId), aryEq(fixture.session.oauthSubjectHash())))
                .thenReturn(Optional.empty());
        when(fixture.contacts.existsOtherByOwnerAndLocatorHash(
                eq(fixture.ownerUserId), any(UUID.class), any(byte[].class)))
                .thenReturn(true);

        BusinessException failure = assertThrows(
                BusinessException.class,
                () -> fixture.service.accept(
                        SESSION_TOKEN, CSRF_TOKEN, IDEMPOTENCY_KEY,
                        true, POLICY_VERSION, WECHAT_ID));

        assertEquals(ErrorCode.INVITATION_UNAVAILABLE, failure.errorCode());
        verify(fixture.contacts, never()).save(any());
    }

    @Test
    void shouldValidateWechatIdBeforeReturningIdempotentReplay() {
        Fixture fixture = fixture(
                InvitationStatus.ACCEPTED,
                InvitationSessionStatus.TERMINATED);
        InvitationSession terminated = new InvitationSession(
                fixture.session.id(),
                fixture.session.invitationId(),
                fixture.session.sessionTokenDigest(),
                fixture.session.csrfTokenDigest(),
                fixture.session.oauthStateDigest(),
                fixture.session.declineIdempotencyKeyDigest(),
                fixture.digestService.sha256(IDEMPOTENCY_KEY),
                null,
                null,
                fixture.session.oauthVerifiedAt(),
                POLICY_VERSION,
                InvitationSessionStatus.TERMINATED,
                fixture.session.expiresAt(),
                fixture.session.createdAt(),
                NOW,
                fixture.session.version());
        when(fixture.sessions.findBySessionTokenDigest(
                aryEq(fixture.digestService.sha256(SESSION_TOKEN))))
                .thenReturn(Optional.of(terminated));
        when(fixture.sessions.findByIdForUpdate(terminated.id()))
                .thenReturn(Optional.of(terminated));

        BusinessException failure = assertThrows(
                BusinessException.class,
                () -> fixture.service.accept(
                        SESSION_TOKEN,
                        CSRF_TOKEN,
                        IDEMPOTENCY_KEY,
                        true,
                        POLICY_VERSION,
                        "not a wechat id"));

        assertEquals(ErrorCode.VALIDATION_FAILED, failure.errorCode());
        verify(fixture.contacts, never()).save(any());
    }

    private Fixture fixture(
            InvitationStatus invitationStatus,
            InvitationSessionStatus sessionStatus) {
        DigestService digestService = new DigestService();
        InvitationSessionRepositoryPort sessions = mock(InvitationSessionRepositoryPort.class);
        InvitationRepositoryPort invitations = mock(InvitationRepositoryPort.class);
        ContactBindingRepositoryPort contacts = mock(ContactBindingRepositoryPort.class);
        SensitiveDataProtector protector = protector();
        AuditEventPort audit = mock(AuditEventPort.class);
        SecretTokenPort secretTokenPort = mock(SecretTokenPort.class);
        when(secretTokenPort.issue()).thenReturn(ROTATED_CSRF_TOKEN);
        UUID invitationId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        ContactInvitation invitation = new ContactInvitation(
                invitationId,
                ownerUserId,
                digestService.sha256("proof-tombstone"),
                invitationStatus,
                NOW.plusSeconds(900),
                NOW.minusSeconds(120),
                digestService.sha256("create-idempotency"),
                null,
                0L);
        InvitationSession session = new InvitationSession(
                UUID.randomUUID(),
                invitationId,
                digestService.sha256(SESSION_TOKEN),
                digestService.sha256(CSRF_TOKEN),
                digestService.sha256("oauth-state-consumed"),
                null,
                null,
                digestService.sha256("relative-subject"),
                new byte[] {1, 2, 3, 4},
                NOW.minusSeconds(30),
                null,
                sessionStatus,
                NOW.plusSeconds(600),
                NOW.minusSeconds(60),
                null,
                0L);
        when(sessions.findBySessionTokenDigest(
                aryEq(digestService.sha256(SESSION_TOKEN))))
                .thenReturn(Optional.of(session));
        when(sessions.findByIdForUpdate(session.id())).thenReturn(Optional.of(session));
        when(invitations.findById(invitationId)).thenReturn(Optional.of(invitation));
        when(invitations.findByIdForUpdate(invitationId, ownerUserId))
                .thenReturn(Optional.of(invitation));
        InvitationSessionProperties properties = new InvitationSessionProperties(
                "__Host-ai_friend_invitation", Duration.ofMinutes(30));
        InvitationAcceptanceService service = new InvitationAcceptanceService(
                sessions,
                invitations,
                contacts,
                secretTokenPort,
                digestService,
                protector,
                audit,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(
                service,
                sessions,
                invitations,
                contacts,
                audit,
                digestService,
                protector,
                invitation,
                session,
                ownerUserId);
    }

    private record Fixture(
            InvitationAcceptanceService service,
            InvitationSessionRepositoryPort sessions,
            InvitationRepositoryPort invitations,
            ContactBindingRepositoryPort contacts,
            AuditEventPort audit,
            DigestService digestService,
            SensitiveDataProtector protector,
            ContactInvitation invitation,
            InvitationSession session,
            UUID ownerUserId) {
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
