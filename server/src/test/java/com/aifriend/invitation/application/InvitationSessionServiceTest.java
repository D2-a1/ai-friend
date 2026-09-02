package com.aifriend.invitation.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.invitation.domain.ContactInvitation;
import com.aifriend.invitation.domain.InvitationSession;
import com.aifriend.invitation.domain.InvitationSessionStatus;
import com.aifriend.invitation.domain.InvitationStatus;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.error.UpstreamFailureException;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;

class InvitationSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-07T02:00:00Z");
    private static final String PROOF = "p".repeat(43);
    private static final String SESSION_TOKEN = "s".repeat(43);
    private static final String CSRF_TOKEN = "c".repeat(43);
    private static final String OAUTH_STATE = "o".repeat(43);

    @Test
    void shouldRedeemProofOnceAndPersistOnlyCredentialDigests() {
        DigestService digestService = new DigestService();
        InMemoryInvitationRepository invitations = new InMemoryInvitationRepository();
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        ContactInvitation invitation = invitation(digestService, InvitationStatus.PENDING, NOW.plusSeconds(3600));
        invitations.save(invitation);
        InvitationSessionService service = service(
                invitations,
                sessions,
                tokens(),
                state -> URI.create("https://invite.example.com/dev/wechat-authorization?state=" + state),
                digestService);

        CreatedInvitationSession created = service.redeem(
                PublicIdCodec.invitationId(invitation.id()), PROOF);

        assertEquals(SESSION_TOKEN, created.sessionToken());
        assertEquals(CSRF_TOKEN, created.csrfToken());
        assertEquals(NOW.plus(Duration.ofMinutes(30)), created.expiresAt());
        ContactInvitation redeemed = invitations.values.get(invitation.id());
        assertEquals(InvitationStatus.PROOF_REDEEMED, redeemed.status());
        assertFalse(Arrays.equals(digestService.sha256(PROOF), redeemed.proofDigest()));
        InvitationSession stored = sessions.session;
        assertEquals(InvitationSessionStatus.AWAITING_WECHAT_OAUTH, stored.status());
        assertArrayEquals(digestService.sha256(SESSION_TOKEN), stored.sessionTokenDigest());
        assertArrayEquals(digestService.sha256(CSRF_TOKEN), stored.csrfTokenDigest());
        assertArrayEquals(digestService.sha256(OAUTH_STATE), stored.oauthStateDigest());

        BusinessException replay = assertThrows(
                BusinessException.class,
                () -> service.redeem(PublicIdCodec.invitationId(invitation.id()), PROOF));
        assertEquals(ErrorCode.INVITATION_UNAVAILABLE, replay.errorCode());
    }

    @Test
    void shouldReturnSameUnavailableErrorForMissingWrongExpiredAndTerminalInvitations() {
        DigestService digestService = new DigestService();
        BusinessException missing = redeemFailure(
                digestService, null, "iv_not-a-uuid", PROOF);
        BusinessException wrongProof = redeemFailure(
                digestService,
                invitation(digestService, InvitationStatus.PENDING, NOW.plusSeconds(60)),
                null,
                "x".repeat(43));
        BusinessException expired = redeemFailure(
                digestService,
                invitation(digestService, InvitationStatus.PENDING, NOW.minusSeconds(1)),
                null,
                PROOF);
        BusinessException revoked = redeemFailure(
                digestService,
                invitation(digestService, InvitationStatus.REVOKED, NOW.plusSeconds(60)),
                null,
                PROOF);

        for (BusinessException exception : new BusinessException[] {missing, wrongProof, expired, revoked}) {
            assertEquals(ErrorCode.INVITATION_UNAVAILABLE, exception.errorCode());
            assertEquals("邀请已失效，请联系邀请人重新发送", exception.getMessage());
        }
    }

    @Test
    void shouldNotMutateInvitationWhenAuthorizationPortFailsClosed() {
        DigestService digestService = new DigestService();
        InMemoryInvitationRepository invitations = new InMemoryInvitationRepository();
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        ContactInvitation invitation = invitation(digestService, InvitationStatus.PENDING, NOW.plusSeconds(60));
        invitations.save(invitation);
        InvitationSessionService service = service(
                invitations,
                sessions,
                tokens(),
                state -> { throw new UpstreamFailureException(); },
                digestService);

        assertThrows(
                UpstreamFailureException.class,
                () -> service.redeem(PublicIdCodec.invitationId(invitation.id()), PROOF));

        assertEquals(InvitationStatus.PENDING, invitations.values.get(invitation.id()).status());
        assertEquals(null, sessions.session);
    }

    private BusinessException redeemFailure(
            DigestService digestService,
            ContactInvitation invitation,
            String publicId,
            String proof) {
        InMemoryInvitationRepository invitations = new InMemoryInvitationRepository();
        if (invitation != null) {
            invitations.save(invitation);
        }
        String targetId = publicId != null
                ? publicId
                : PublicIdCodec.invitationId(invitation.id());
        InvitationSessionService service = service(
                invitations,
                new InMemorySessionRepository(),
                tokens(),
                state -> URI.create("https://invite.example.com/dev?state=" + state),
                digestService);
        return assertThrows(BusinessException.class, () -> service.redeem(targetId, proof));
    }

    private ContactInvitation invitation(
            DigestService digestService,
            InvitationStatus status,
            Instant expiresAt) {
        return new ContactInvitation(
                UUID.randomUUID(),
                UUID.randomUUID(),
                digestService.sha256(PROOF),
                status,
                expiresAt,
                NOW.minusSeconds(60),
                digestService.sha256("create-idempotency"),
                null,
                0L);
    }

    private SecretTokenPort tokens() {
        Queue<String> values = new ArrayDeque<>();
        values.add(SESSION_TOKEN);
        values.add(CSRF_TOKEN);
        values.add(OAUTH_STATE);
        return values::remove;
    }

    private InvitationSessionService service(
            InvitationRepositoryPort invitations,
            InvitationSessionRepositoryPort sessions,
            SecretTokenPort tokens,
            WechatInvitationAuthorizationPort authorization,
            DigestService digestService) {
        return new InvitationSessionService(
                invitations,
                sessions,
                tokens,
                authorization,
                digestService,
                (actorUserId, action, result, reasonCode, occurredAt) -> { },
                new InvitationSessionProperties(
                        "__Host-ai_friend_invitation",
                        Duration.ofMinutes(30)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class InMemorySessionRepository implements InvitationSessionRepositoryPort {

        private InvitationSession session;

        @Override
        public Optional<InvitationSession> findBySessionTokenDigest(byte[] sessionTokenDigest) {
            if (session == null || !Arrays.equals(
                    session.sessionTokenDigest(), sessionTokenDigest)) {
                return Optional.empty();
            }
            return Optional.of(session);
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
            if (session == null || !session.invitationId().equals(invitationId)) {
                return 0;
            }
            session = new InvitationSession(
                    session.id(), session.invitationId(), session.sessionTokenDigest(),
                    session.csrfTokenDigest(), session.oauthStateDigest(),
                    session.declineIdempotencyKeyDigest(),
                    InvitationSessionStatus.TERMINATED, session.expiresAt(), session.createdAt(),
                    now, session.version() + 1);
            return 1;
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
