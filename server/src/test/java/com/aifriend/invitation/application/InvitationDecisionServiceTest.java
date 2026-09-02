package com.aifriend.invitation.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.invitation.domain.ContactInvitation;
import com.aifriend.invitation.domain.InvitationSession;
import com.aifriend.invitation.domain.InvitationSessionStatus;
import com.aifriend.invitation.domain.InvitationStatus;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;

class InvitationDecisionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-07T03:00:00Z");
    private static final String SESSION_TOKEN = "s".repeat(43);
    private static final String CSRF_TOKEN = "c".repeat(43);
    private static final String IDEMPOTENCY_KEY = "01JINVITATIONDECLINE000000001";

    @Test
    void shouldDeclineAtomicallyAndReplaySameDecision() {
        Fixture fixture = fixture(
                InvitationStatus.PROOF_REDEEMED,
                InvitationSessionStatus.AWAITING_WECHAT_OAUTH,
                NOW.plusSeconds(600));

        fixture.service.decline(SESSION_TOKEN, CSRF_TOKEN, IDEMPOTENCY_KEY, true);

        ContactInvitation declined = fixture.invitations.values.get(fixture.invitationId);
        InvitationSession terminated = fixture.sessions.session;
        assertEquals(InvitationStatus.DECLINED, declined.status());
        assertEquals(InvitationSessionStatus.TERMINATED, terminated.status());
        assertEquals(NOW, terminated.terminatedAt());
        assertArrayEquals(
                fixture.digestService.sha256(IDEMPOTENCY_KEY),
                terminated.declineIdempotencyKeyDigest());
        assertEquals(1, fixture.audit.count);

        fixture.service.decline(SESSION_TOKEN, CSRF_TOKEN, IDEMPOTENCY_KEY, true);

        assertEquals(InvitationStatus.DECLINED,
                fixture.invitations.values.get(fixture.invitationId).status());
        assertEquals(1, fixture.audit.count);
    }

    @Test
    void shouldReturnSameUnavailableErrorForForgedWrongExpiredAndTerminalSessions() {
        Fixture forged = fixture(
                InvitationStatus.PROOF_REDEEMED,
                InvitationSessionStatus.AWAITING_WECHAT_OAUTH,
                NOW.plusSeconds(600));
        Fixture wrongCsrf = fixture(
                InvitationStatus.PROOF_REDEEMED,
                InvitationSessionStatus.AWAITING_WECHAT_OAUTH,
                NOW.plusSeconds(600));
        Fixture expired = fixture(
                InvitationStatus.PROOF_REDEEMED,
                InvitationSessionStatus.AWAITING_WECHAT_OAUTH,
                NOW.minusSeconds(1));
        Fixture terminal = fixture(
                InvitationStatus.REVOKED,
                InvitationSessionStatus.TERMINATED,
                NOW.plusSeconds(600));

        BusinessException forgedFailure = assertThrows(BusinessException.class,
                () -> forged.service.decline(
                        "x".repeat(43), CSRF_TOKEN, IDEMPOTENCY_KEY, true));
        BusinessException csrfFailure = assertThrows(BusinessException.class,
                () -> wrongCsrf.service.decline(
                        SESSION_TOKEN, "x".repeat(43), IDEMPOTENCY_KEY, true));
        BusinessException expiredFailure = assertThrows(BusinessException.class,
                () -> expired.service.decline(
                        SESSION_TOKEN, CSRF_TOKEN, IDEMPOTENCY_KEY, true));
        BusinessException terminalFailure = assertThrows(BusinessException.class,
                () -> terminal.service.decline(
                        SESSION_TOKEN, CSRF_TOKEN, IDEMPOTENCY_KEY, true));

        for (BusinessException exception : new BusinessException[] {
                forgedFailure, csrfFailure, expiredFailure, terminalFailure}) {
            assertEquals(ErrorCode.INVITATION_UNAVAILABLE, exception.errorCode());
            assertEquals("邀请已失效，请联系邀请人重新发送", exception.getMessage());
        }
    }

    @Test
    void shouldRejectDifferentReplayKeyAndNonExplicitDecision() {
        Fixture fixture = fixture(
                InvitationStatus.WECHAT_VERIFIED,
                InvitationSessionStatus.WECHAT_VERIFIED,
                NOW.plusSeconds(600));
        fixture.service.decline(SESSION_TOKEN, CSRF_TOKEN, IDEMPOTENCY_KEY, true);

        BusinessException differentKey = assertThrows(BusinessException.class,
                () -> fixture.service.decline(
                        SESSION_TOKEN, CSRF_TOKEN, "01JINVITATIONDECLINE000000002", true));
        assertEquals(ErrorCode.INVITATION_UNAVAILABLE, differentKey.errorCode());

        Fixture unconfirmed = fixture(
                InvitationStatus.PROOF_REDEEMED,
                InvitationSessionStatus.AWAITING_WECHAT_OAUTH,
                NOW.plusSeconds(600));
        BusinessException validation = assertThrows(BusinessException.class,
                () -> unconfirmed.service.decline(
                        SESSION_TOKEN, CSRF_TOKEN, IDEMPOTENCY_KEY, false));
        assertEquals(ErrorCode.VALIDATION_FAILED, validation.errorCode());
    }

    private Fixture fixture(
            InvitationStatus invitationStatus,
            InvitationSessionStatus sessionStatus,
            Instant expiresAt) {
        DigestService digestService = new DigestService();
        InMemoryInvitationRepository invitations = new InMemoryInvitationRepository();
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        CountingAuditEventPort audit = new CountingAuditEventPort();
        UUID invitationId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        invitations.save(new ContactInvitation(
                invitationId,
                ownerUserId,
                digestService.sha256("proof-tombstone"),
                invitationStatus,
                expiresAt,
                NOW.minusSeconds(120),
                digestService.sha256("create-idempotency"),
                null,
                0L));
        sessions.save(new InvitationSession(
                UUID.randomUUID(),
                invitationId,
                digestService.sha256(SESSION_TOKEN),
                digestService.sha256(CSRF_TOKEN),
                digestService.sha256("oauth-state"),
                null,
                sessionStatus,
                expiresAt,
                NOW.minusSeconds(60),
                sessionStatus == InvitationSessionStatus.TERMINATED ? NOW.minusSeconds(1) : null,
                0L));
        InvitationDecisionService service = new InvitationDecisionService(
                sessions,
                invitations,
                digestService,
                audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(service, invitations, sessions, digestService, audit, invitationId);
    }

    private record Fixture(
            InvitationDecisionService service,
            InMemoryInvitationRepository invitations,
            InMemorySessionRepository sessions,
            DigestService digestService,
            CountingAuditEventPort audit,
            UUID invitationId) {
    }

    private static final class CountingAuditEventPort implements AuditEventPort {

        private int count;

        @Override
        public void append(UUID actorUserId, String action, String result,
                String reasonCode, Instant occurredAt) {
            count++;
        }
    }

    private static final class InMemorySessionRepository implements InvitationSessionRepositoryPort {

        private InvitationSession session;

        @Override
        public Optional<InvitationSession> findBySessionTokenDigest(byte[] sessionTokenDigest) {
            return session != null && Arrays.equals(
                    session.sessionTokenDigest(), sessionTokenDigest)
                    ? Optional.of(session) : Optional.empty();
        }

        @Override
        public Optional<InvitationSession> findByIdForUpdate(UUID sessionId) {
            return session != null && session.id().equals(sessionId)
                    ? Optional.of(session) : Optional.empty();
        }

        @Override
        public InvitationSession save(InvitationSession value) {
            session = value;
            return value;
        }

        @Override
        public int terminateByInvitationId(UUID invitationId, Instant now) {
            return 0;
        }
    }

    private static final class InMemoryInvitationRepository implements InvitationRepositoryPort {

        private final Map<UUID, ContactInvitation> values = new LinkedHashMap<>();

        @Override
        public void lockOwner(UUID ownerUserId) {
            // 单线程单元测试不需要模拟数据库行锁。
        }

        @Override
        public void expirePending(UUID ownerUserId, Instant now) {
            // 本测试直接构造目标状态。
        }

        @Override
        public Optional<ContactInvitation> findByCreateIdempotencyKey(
                UUID ownerUserId, byte[] idempotencyKeyHash) {
            return Optional.empty();
        }

        @Override
        public Optional<ContactInvitation> findByIdForUpdate(UUID invitationId, UUID ownerUserId) {
            return Optional.ofNullable(values.get(invitationId))
                    .filter(invitation -> invitation.ownerUserId().equals(ownerUserId));
        }

        @Override
        public Optional<ContactInvitation> findByIdForUpdate(UUID invitationId) {
            return Optional.ofNullable(values.get(invitationId));
        }

        @Override
        public long countPending(UUID ownerUserId) {
            return 0;
        }

        @Override
        public java.util.List<ContactInvitation> findPending(
                UUID ownerUserId, Instant now, int limit) {
            return java.util.List.of();
        }

        @Override
        public long countCreatedSince(UUID ownerUserId, Instant dayStart) {
            return 0;
        }

        @Override
        public ContactInvitation save(ContactInvitation invitation) {
            values.put(invitation.id(), invitation);
            return invitation;
        }
    }
}
