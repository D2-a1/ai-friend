package com.aifriend.contact.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aifriend.contact.domain.ContactBinding;
import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.shared.security.SecurityKeyMaterial;
import com.aifriend.shared.security.SensitiveDataProtector;

class ContactUnbindServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-09T05:00:00Z");
    private static final String IDEMPOTENCY_KEY = "01JCONTACTUNBIND000000000001";

    private ContactBindingRepositoryPort repository;
    private ContactCleanupOutboxPort cleanupOutboxPort;
    private AuditEventPort auditEventPort;
    private DigestService digestService;
    private ContactUnbindService service;

    @BeforeEach
    void setUp() {
        repository = mock(ContactBindingRepositoryPort.class);
        cleanupOutboxPort = mock(ContactCleanupOutboxPort.class);
        auditEventPort = mock(AuditEventPort.class);
        digestService = new DigestService();
        SensitiveDataProtector protector = protector();
        service = new ContactUnbindService(
                repository,
                cleanupOutboxPort,
                digestService,
                new ContactSummaryMapper(
                        protector,
                        new ContactAliasMapper(protector, mock(AcousticTemplatePort.class))),
                auditEventPort,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldRevokeClearSensitiveFieldsAndAppendCleanupEvent() {
        ContactBinding active = activeBinding();
        when(repository.findByOwnerAndIdForUpdate(active.ownerUserId(), active.id()))
                .thenReturn(Optional.of(active));
        when(repository.save(any(ContactBinding.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ContactSummary response = service.unbind(
                active.ownerUserId(), PublicIdCodec.contactId(active.id()),
                IDEMPOTENCY_KEY, new ContactUnbindCommand(true, 2));

        ArgumentCaptor<ContactBinding> captor = ArgumentCaptor.forClass(ContactBinding.class);
        verify(repository).lockOwner(active.ownerUserId());
        verify(repository).save(captor.capture());
        ContactBinding revoked = captor.getValue();
        assertEquals(ContactStatus.REVOKED, revoked.status());
        assertArrayEquals(active.contactSubjectHash(), revoked.contactSubjectHash());
        assertNull(revoked.contactSubjectCipher());
        assertNull(revoked.wechatLocatorCipher());
        assertNull(revoked.wechatLocatorHash());
        assertNull(revoked.remarkCipher());
        assertNull(revoked.wechatVersion());
        assertNull(revoked.localVerificationVersion());
        assertNull(revoked.verifiedAt());
        assertNull(revoked.relationship());
        assertEquals(NOW, revoked.revokedAt());
        assertEquals(ContactStatus.REVOKED, response.status());
        assertNull(response.remark());
        verify(cleanupOutboxPort).appendUnbound(active.id(), active.ownerUserId(), NOW);
        verify(auditEventPort).append(
                active.ownerUserId(), "CONTACT_UNBIND", "SUCCESS", null, NOW);
    }

    @Test
    void shouldReplaySameKeyAndBodyWithoutDuplicateSideEffects() {
        ContactUnbindCommand command = new ContactUnbindCommand(true, 2);
        ContactBinding replayed = revokedBinding(
                digestService.sha256(IDEMPOTENCY_KEY),
                digestService.sha256(command.fingerprintInput()));
        when(repository.findByOwnerAndIdForUpdate(replayed.ownerUserId(), replayed.id()))
                .thenReturn(Optional.of(replayed));

        ContactSummary response = service.unbind(
                replayed.ownerUserId(), PublicIdCodec.contactId(replayed.id()),
                IDEMPOTENCY_KEY, command);

        assertEquals(ContactStatus.REVOKED, response.status());
        verify(repository, never()).save(any(ContactBinding.class));
        verify(cleanupOutboxPort, never()).appendUnbound(any(), any(), any());
        verify(auditEventPort, never()).append(any(), any(), any(), any(), any());
    }

    @Test
    void shouldRejectSameKeyWithDifferentBody() {
        ContactBinding replayed = revokedBinding(
                digestService.sha256(IDEMPOTENCY_KEY), new byte[32]);
        when(repository.findByOwnerAndIdForUpdate(replayed.ownerUserId(), replayed.id()))
                .thenReturn(Optional.of(replayed));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.unbind(
                        replayed.ownerUserId(), PublicIdCodec.contactId(replayed.id()),
                        IDEMPOTENCY_KEY, new ContactUnbindCommand(true, 2)));

        assertEquals(ErrorCode.SESSION_CONFLICT, exception.errorCode());
    }

    @Test
    void shouldRejectMissingConfirmationStaleVersionAndDifferentKeyAfterRevoked() {
        ContactBinding active = activeBinding();
        when(repository.findByOwnerAndIdForUpdate(active.ownerUserId(), active.id()))
                .thenReturn(Optional.of(active));

        BusinessException confirmationException = assertThrows(
                BusinessException.class,
                () -> service.unbind(
                        active.ownerUserId(), PublicIdCodec.contactId(active.id()),
                        IDEMPOTENCY_KEY, new ContactUnbindCommand(false, 2)));
        assertEquals(ErrorCode.VALIDATION_FAILED, confirmationException.errorCode());

        BusinessException versionException = assertThrows(
                BusinessException.class,
                () -> service.unbind(
                        active.ownerUserId(), PublicIdCodec.contactId(active.id()),
                        IDEMPOTENCY_KEY, new ContactUnbindCommand(true, 1)));
        assertEquals(ErrorCode.SESSION_CONFLICT, versionException.errorCode());

        ContactBinding revoked = revokedBinding(new byte[32], new byte[32]);
        when(repository.findByOwnerAndIdForUpdate(revoked.ownerUserId(), revoked.id()))
                .thenReturn(Optional.of(revoked));
        BusinessException stateException = assertThrows(
                BusinessException.class,
                () -> service.unbind(
                        revoked.ownerUserId(), PublicIdCodec.contactId(revoked.id()),
                        "01JCONTACTUNBIND000000000002",
                        new ContactUnbindCommand(true, 3)));
        assertEquals(ErrorCode.SESSION_CONFLICT, stateException.errorCode());
    }

    @Test
    void shouldReturnNotFoundForContactOutsideOwnerScope() {
        UUID ownerUserId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        when(repository.findByOwnerAndIdForUpdate(ownerUserId, contactId))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.unbind(
                        ownerUserId, PublicIdCodec.contactId(contactId), IDEMPOTENCY_KEY,
                        new ContactUnbindCommand(true, 2)));

        assertEquals(ErrorCode.NOT_FOUND, exception.errorCode());
        verify(repository, never()).save(any(ContactBinding.class));
        verify(cleanupOutboxPort, never()).appendUnbound(any(), any(), any());
    }

    private ContactBinding activeBinding() {
        UUID ownerUserId = UUID.randomUUID();
        return new ContactBinding(
                UUID.randomUUID(), ownerUserId, new byte[] {1}, new byte[] {2},
                new byte[] {3}, new byte[] {4}, new byte[] {5},
                "8.0.56", "wechat-contact-v1", new byte[] {6}, new byte[] {7},
                null, null, NOW.minus(Duration.ofMinutes(1)), "女儿",
                "invitation-consent-v1", NOW.minus(Duration.ofDays(1)),
                ContactStatus.ACTIVE_NO_ALIAS, ownerUserId, 1L,
                NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofMinutes(1)), null);
    }

    private ContactBinding revokedBinding(byte[] idempotencyHash, byte[] requestHash) {
        UUID ownerUserId = UUID.randomUUID();
        return new ContactBinding(
                UUID.randomUUID(), ownerUserId, new byte[] {1}, null,
                null, null, null, null, null, null, null,
                idempotencyHash, requestHash, null, null,
                "invitation-consent-v1", NOW.minus(Duration.ofDays(1)),
                ContactStatus.REVOKED, ownerUserId, 2L,
                NOW.minus(Duration.ofDays(2)), NOW, NOW);
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
