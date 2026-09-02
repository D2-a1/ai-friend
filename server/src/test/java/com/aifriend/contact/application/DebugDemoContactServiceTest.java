package com.aifriend.contact.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aifriend.contact.domain.ContactBinding;
import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.security.SecurityKeyMaterial;
import com.aifriend.shared.security.SensitiveDataProtector;

class DebugDemoContactServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-29T04:00:00Z");

    private ContactBindingRepositoryPort bindingRepositoryPort;
    private ContactAliasRepositoryPort aliasRepositoryPort;
    private SensitiveDataProtector protector;
    private DebugDemoContactService service;
    private UUID ownerUserId;

    @BeforeEach
    void setUp() {
        bindingRepositoryPort = mock(ContactBindingRepositoryPort.class);
        aliasRepositoryPort = mock(ContactAliasRepositoryPort.class);
        protector = protector();
        ownerUserId = UUID.randomUUID();
        ContactSummaryMapper mapper = new ContactSummaryMapper(
                protector,
                new ContactAliasMapper(
                        protector, mock(AcousticTemplatePort.class)));
        service = new DebugDemoContactService(
                bindingRepositoryPort, aliasRepositoryPort, mapper, protector,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(bindingRepositoryPort.save(any(ContactBinding.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void shouldCreateEncryptedOwnerScopedContactWithoutWechatLocator() {
        when(bindingRepositoryPort.findByOwnerAndSubjectForUpdate(
                any(), any())).thenReturn(Optional.empty());

        ContactSummary result = service.ensure(ownerUserId);

        assertEquals("体验联系人", result.remark());
        assertEquals("仅用于本机体验", result.relationship());
        assertEquals(ContactStatus.ACTIVE_NO_ALIAS, result.status());
        assertNull(result.localVerificationVersion());
        assertNull(result.verifiedAt());
        verify(bindingRepositoryPort).lockOwner(ownerUserId);
        verify(bindingRepositoryPort).countOccupying(ownerUserId);
        verify(bindingRepositoryPort).save(any(ContactBinding.class));
    }

    @Test
    void shouldReturnExistingActiveContactWithoutCreatingAnotherObject() {
        ContactBinding existing = binding(ContactStatus.ACTIVE_NO_ALIAS, null);
        when(bindingRepositoryPort.findByOwnerAndSubjectForUpdate(
                any(), any())).thenReturn(Optional.of(existing));

        ContactSummary result = service.ensure(ownerUserId);

        assertEquals(existing.id(), com.aifriend.shared.security.PublicIdCodec
                .parseContactId(result.id()));
        verify(bindingRepositoryPort, never()).countOccupying(ownerUserId);
        verify(bindingRepositoryPort, never()).save(any(ContactBinding.class));
    }

    @Test
    void shouldRejectRevokedDemoContactWithoutBlockingCleanupConsumer() {
        ContactBinding revoked = binding(ContactStatus.REVOKED, NOW.minusSeconds(60));
        when(bindingRepositoryPort.findByOwnerAndSubjectForUpdate(
                any(), any())).thenReturn(Optional.of(revoked));

        assertThrows(BusinessException.class, () -> service.ensure(ownerUserId));

        verify(bindingRepositoryPort, never()).save(any(ContactBinding.class));
    }

    private ContactBinding binding(ContactStatus status, Instant revokedAt) {
        return new ContactBinding(
                UUID.randomUUID(), ownerUserId,
                protector.subjectHmac(DebugDemoContactService.SUBJECT),
                protector.encrypt(DebugDemoContactService.SUBJECT),
                null, null, protector.encrypt("体验联系人"),
                null, "debug-mvp-demo-v1", null, null, null, null,
                NOW, "仅用于本机体验", "debug-mvp-demo-v1", NOW,
                status, ownerUserId, 1L, NOW.minusSeconds(120), NOW, revokedAt);
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
