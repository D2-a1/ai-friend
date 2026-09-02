package com.aifriend.invitation.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.aifriend.invitation.domain.ContactInvitation;
import com.aifriend.contact.application.ContactBindingRepositoryPort;
import com.aifriend.invitation.domain.InvitationStatus;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.shared.security.SecurityKeyMaterial;
import com.aifriend.shared.security.SensitiveDataProtector;

class InvitationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-07T01:00:00Z");

    @Test
    void shouldCreateFragmentOnlyInvitationAndReplaySameIdempotentResult() {
        InMemoryInvitationRepository repository = new InMemoryInvitationRepository();
        DigestService digestService = new DigestService();
        InvitationService service = service(repository, digestService);
        UUID ownerUserId = UUID.randomUUID();

        CreatedInvitation first = service.create(ownerUserId, "01JINVITATIONCREATE000000001");
        CreatedInvitation replay = service.create(ownerUserId, "01JINVITATIONCREATE000000001");

        assertEquals(first, replay);
        assertEquals(1, repository.invitations.size());
        assertEquals(NOW.plus(Duration.ofHours(24)), first.expiresAt());
        assertTrue(first.shareUrl().startsWith("https://invite.example.com/invite/" + first.invitationId()));
        assertFalse(first.shareUrl().contains("?"));
        String proof = first.shareUrl().substring(first.shareUrl().indexOf("#proof=") + 7);
        assertEquals(43, proof.length());
        assertArrayEquals(digestService.sha256(proof), repository.invitations.values().iterator().next().proofDigest());
    }

    @Test
    void shouldRejectSixthUnfinishedInvitation() {
        InMemoryInvitationRepository repository = new InMemoryInvitationRepository();
        InvitationService service = service(repository, new DigestService());
        UUID ownerUserId = UUID.randomUUID();
        for (int index = 0; index < 5; index++) {
            service.create(ownerUserId, "01JINVITATIONPENDING0000000" + index);
        }

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.create(ownerUserId, "01JINVITATIONPENDING00000005"));

        assertEquals(ErrorCode.CONTACT_LIMIT_REACHED, exception.errorCode());
        assertEquals(5, repository.invitations.size());
    }

    @Test
    void shouldCountAllDailyCreationsEvenAfterRevocation() {
        InMemoryInvitationRepository repository = new InMemoryInvitationRepository();
        InvitationService service = service(repository, new DigestService());
        UUID ownerUserId = UUID.randomUUID();
        for (int index = 0; index < 10; index++) {
            CreatedInvitation invitation = service.create(
                    ownerUserId, "01JINVITATIONDAILYCREATE000" + index);
            service.revoke(
                    ownerUserId,
                    invitation.invitationId(),
                    "01JINVITATIONDAILYREVOKE000" + index);
        }

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.create(ownerUserId, "01JINVITATIONDAILYCREATE0010"));

        assertEquals(ErrorCode.RATE_LIMITED, exception.errorCode());
        assertEquals(10, repository.invitations.size());
    }

    @Test
    void shouldRevokeOnlyOwnerInvitationAndKeepTerminalState() {
        InMemoryInvitationRepository repository = new InMemoryInvitationRepository();
        InvitationService service = service(repository, new DigestService());
        UUID ownerUserId = UUID.randomUUID();
        CreatedInvitation invitation = service.create(ownerUserId, "01JINVITATIONOWNERCREATE00001");

        BusinessException notFound = assertThrows(
                BusinessException.class,
                () -> service.revoke(
                        UUID.randomUUID(), invitation.invitationId(), "01JINVITATIONOTHERREVOKE00001"));
        assertEquals(ErrorCode.NOT_FOUND, notFound.errorCode());

        service.revoke(ownerUserId, invitation.invitationId(), "01JINVITATIONOWNERREVOKE00001");
        service.revoke(ownerUserId, invitation.invitationId(), "01JINVITATIONOWNERREVOKE00002");

        ContactInvitation stored = repository.invitations.values().iterator().next();
        assertEquals(InvitationStatus.REVOKED, stored.status());
        assertTrue(stored.revokeIdempotencyKeyHash().length > 0);
    }

    @Test
    void shouldTerminateRestrictedSessionWhenRedeemedInvitationIsRevoked() {
        InMemoryInvitationRepository repository = new InMemoryInvitationRepository();
        CountingInvitationSessionRepository sessions = new CountingInvitationSessionRepository();
        InvitationService service = service(repository, new DigestService(), sessions);
        UUID ownerUserId = UUID.randomUUID();
        CreatedInvitation created = service.create(ownerUserId, "01JINVITATIONSESSIONCREATE001");
        UUID invitationId = PublicIdCodec.parseInvitationId(created.invitationId());
        ContactInvitation pending = repository.invitations.get(invitationId);
        repository.save(copyWithStatus(pending, InvitationStatus.PROOF_REDEEMED));

        service.revoke(ownerUserId, created.invitationId(), "01JINVITATIONSESSIONREVOKE001");

        assertEquals(1, sessions.terminateCount);
        assertEquals(invitationId, sessions.terminatedInvitationId);
        assertEquals(InvitationStatus.REVOKED, repository.invitations.get(invitationId).status());
    }

    @Test
    void shouldListOnlyCurrentOwnerUnexpiredInvitationsWithoutShareUrl() {
        InMemoryInvitationRepository repository = new InMemoryInvitationRepository();
        InvitationService service = service(repository, new DigestService());
        UUID ownerUserId = UUID.randomUUID();
        CreatedInvitation active = service.create(
                ownerUserId, "01JINVITATIONLISTACTIVE00001");
        service.create(UUID.randomUUID(), "01JINVITATIONLISTOTHER000001");
        ContactInvitation activeDomain = repository.invitations.get(
                PublicIdCodec.parseInvitationId(active.invitationId()));
        repository.save(new ContactInvitation(
                UUID.randomUUID(),
                ownerUserId,
                activeDomain.proofDigest(),
                InvitationStatus.PENDING,
                NOW,
                NOW.minusSeconds(60),
                new DigestService().sha256("expired-idempotency"),
                null,
                0L));

        List<PendingInvitationSummary> summaries = service.listPending(ownerUserId);

        assertEquals(1, summaries.size());
        assertEquals(active.invitationId(), summaries.get(0).invitationId());
        assertEquals(NOW.plus(Duration.ofHours(24)), summaries.get(0).expiresAt());
    }

    private InvitationService service(InMemoryInvitationRepository repository, DigestService digestService) {
        return service(repository, digestService, new NoOpInvitationSessionRepository());
    }

    private InvitationService service(
            InMemoryInvitationRepository repository,
            DigestService digestService,
            InvitationSessionRepositoryPort sessionRepository) {
        byte[] keyBytes = new byte[32];
        for (int index = 0; index < keyBytes.length; index++) {
            keyBytes[index] = (byte) (index + 1);
        }
        SecurityKeyMaterial keyMaterial = new SecurityKeyMaterial(
                new SecretKeySpec(keyBytes, "HmacSHA256"),
                new SecretKeySpec(keyBytes, "AES"),
                new SecretKeySpec(keyBytes, "HmacSHA256"));
        return new InvitationService(
                repository,
                sessionRepository,
                mock(ContactBindingRepositoryPort.class),
                new SensitiveDataProtector(keyMaterial),
                digestService,
                (actorUserId, action, result, reasonCode, occurredAt) -> { },
                new InvitationProperties(
                        URI.create("https://invite.example.com/invite/"),
                        Duration.ofHours(24)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ContactInvitation copyWithStatus(ContactInvitation invitation, InvitationStatus status) {
        return new ContactInvitation(
                invitation.id(), invitation.ownerUserId(), invitation.proofDigest(), status,
                invitation.expiresAt(), invitation.createdAt(), invitation.createIdempotencyKeyHash(),
                invitation.revokeIdempotencyKeyHash(), invitation.version());
    }

    private static final class NoOpInvitationSessionRepository implements InvitationSessionRepositoryPort {

        @Override
        public Optional<com.aifriend.invitation.domain.InvitationSession> findBySessionTokenDigest(
                byte[] sessionTokenDigest) {
            return Optional.empty();
        }

        @Override
        public Optional<com.aifriend.invitation.domain.InvitationSession> findByIdForUpdate(UUID sessionId) {
            return Optional.empty();
        }

        @Override
        public com.aifriend.invitation.domain.InvitationSession save(
                com.aifriend.invitation.domain.InvitationSession session) {
            return session;
        }

        @Override
        public int terminateByInvitationId(UUID invitationId, Instant now) {
            return 0;
        }
    }

    private static final class CountingInvitationSessionRepository
            implements InvitationSessionRepositoryPort {

        private int terminateCount;
        private UUID terminatedInvitationId;

        @Override
        public Optional<com.aifriend.invitation.domain.InvitationSession> findBySessionTokenDigest(
                byte[] sessionTokenDigest) {
            return Optional.empty();
        }

        @Override
        public Optional<com.aifriend.invitation.domain.InvitationSession> findByIdForUpdate(UUID sessionId) {
            return Optional.empty();
        }

        @Override
        public com.aifriend.invitation.domain.InvitationSession save(
                com.aifriend.invitation.domain.InvitationSession session) {
            return session;
        }

        @Override
        public int terminateByInvitationId(UUID invitationId, Instant now) {
            terminateCount++;
            terminatedInvitationId = invitationId;
            return 1;
        }
    }

    private static final class InMemoryInvitationRepository implements InvitationRepositoryPort {

        private final Map<UUID, ContactInvitation> invitations = new LinkedHashMap<>();

        @Override
        public void lockOwner(UUID ownerUserId) {
            // 单线程单元测试不需要模拟数据库行锁。
        }

        @Override
        public void expirePending(UUID ownerUserId, Instant now) {
            List<ContactInvitation> expired = new ArrayList<>();
            invitations.values().stream()
                    .filter(invitation -> invitation.ownerUserId().equals(ownerUserId))
                    .filter(invitation -> invitation.status().isUnfinished())
                    .filter(invitation -> !invitation.expiresAt().isAfter(now))
                    .forEach(invitation -> expired.add(copyWithStatus(invitation, InvitationStatus.EXPIRED)));
            expired.forEach(invitation -> invitations.put(invitation.id(), invitation));
        }

        @Override
        public Optional<ContactInvitation> findByCreateIdempotencyKey(
                UUID ownerUserId, byte[] idempotencyKeyHash) {
            return invitations.values().stream()
                    .filter(invitation -> invitation.ownerUserId().equals(ownerUserId))
                    .filter(invitation -> java.util.Arrays.equals(
                            invitation.createIdempotencyKeyHash(), idempotencyKeyHash))
                    .findFirst();
        }

        @Override
        public Optional<ContactInvitation> findByIdForUpdate(UUID invitationId, UUID ownerUserId) {
            return Optional.ofNullable(invitations.get(invitationId))
                    .filter(invitation -> invitation.ownerUserId().equals(ownerUserId));
        }

        @Override
        public Optional<ContactInvitation> findByIdForUpdate(UUID invitationId) {
            return Optional.ofNullable(invitations.get(invitationId));
        }

        @Override
        public long countPending(UUID ownerUserId) {
            return invitations.values().stream()
                    .filter(invitation -> invitation.ownerUserId().equals(ownerUserId))
                    .filter(invitation -> invitation.status().isUnfinished())
                    .count();
        }

        @Override
        public List<ContactInvitation> findPending(UUID ownerUserId, Instant now, int limit) {
            return invitations.values().stream()
                    .filter(invitation -> invitation.ownerUserId().equals(ownerUserId))
                    .filter(invitation -> invitation.status().isUnfinished())
                    .filter(invitation -> invitation.expiresAt().isAfter(now))
                    .sorted(java.util.Comparator.comparing(ContactInvitation::createdAt).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public long countCreatedSince(UUID ownerUserId, Instant dayStart) {
            return invitations.values().stream()
                    .filter(invitation -> invitation.ownerUserId().equals(ownerUserId))
                    .filter(invitation -> !invitation.createdAt().isBefore(dayStart))
                    .count();
        }

        @Override
        public ContactInvitation save(ContactInvitation invitation) {
            invitations.put(invitation.id(), invitation);
            return invitation;
        }

        private ContactInvitation copyWithStatus(ContactInvitation invitation, InvitationStatus status) {
            return new ContactInvitation(
                    invitation.id(), invitation.ownerUserId(), invitation.proofDigest(), status,
                    invitation.expiresAt(), invitation.createdAt(), invitation.createIdempotencyKeyHash(),
                    invitation.revokeIdempotencyKeyHash(), invitation.version() + 1);
        }
    }
}
