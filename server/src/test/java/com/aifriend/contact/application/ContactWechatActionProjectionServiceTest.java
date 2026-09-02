package com.aifriend.contact.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.contact.domain.ContactBinding;
import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.task.application.WechatActionContactSnapshot;

class ContactWechatActionProjectionServiceTest {

    @Test
    void activeContactAllowsDifferentCurrentWechatVersionAndReturnsRedactedProjection() {
        UUID ownerUserId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ContactBindingRepositoryPort repository = mock(ContactBindingRepositoryPort.class);
        SensitiveDataProtector protector = mock(SensitiveDataProtector.class);
        ContactBinding binding = binding(ownerUserId, contactId, ContactStatus.ACTIVE);
        when(repository.findByOwnerAndIdForUpdate(ownerUserId, contactId))
                .thenReturn(Optional.of(binding));
        when(protector.decrypt(binding.wechatLocatorCipher()))
                .thenReturn("private-stable-locator");
        ContactWechatActionProjectionService service =
                new ContactWechatActionProjectionService(repository, protector);

        Optional<WechatActionContactSnapshot> result = service.findVerifiedForUpdate(
                ownerUserId, contactId, "8.0.76", "locator-v1", false);

        assertTrue(result.isPresent());
        assertFalse(result.orElseThrow().toString().contains("private-stable-locator"));
        assertEquals("8.0.56", result.orElseThrow().wechatVersion());
        verify(repository).findByOwnerAndIdForUpdate(ownerUserId, contactId);
    }

    @Test
    void inactiveLocatorVersionMismatchOrBrokenCipherReturnsNoProjection() {
        UUID ownerUserId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ContactBindingRepositoryPort repository = mock(ContactBindingRepositoryPort.class);
        SensitiveDataProtector protector = mock(SensitiveDataProtector.class);
        ContactWechatActionProjectionService service =
                new ContactWechatActionProjectionService(repository, protector);

        when(repository.findByOwnerAndIdForUpdate(ownerUserId, contactId))
                .thenReturn(Optional.of(binding(
                        ownerUserId, contactId, ContactStatus.ACTIVE_NO_ALIAS)));
        assertTrue(service.findVerifiedForUpdate(
                ownerUserId, contactId, "8.0.56", "locator-v1", false).isEmpty());

        ContactBinding active = binding(ownerUserId, contactId, ContactStatus.ACTIVE);
        when(repository.findByOwnerAndIdForUpdate(ownerUserId, contactId))
                .thenReturn(Optional.of(active));
        assertTrue(service.findVerifiedForUpdate(
                ownerUserId, contactId, "8.0.76", "locator-v2", false).isEmpty());

        when(protector.decrypt(active.wechatLocatorCipher()))
                .thenThrow(new IllegalStateException("test cipher failure"));
        assertTrue(service.findVerifiedForUpdate(
                ownerUserId, contactId, "8.0.76", "locator-v1", false).isEmpty());
    }

    @Test
    void messageWechatVersionMismatchMustFailBeforeDecryptingLocator() {
        UUID ownerUserId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ContactBindingRepositoryPort repository = mock(ContactBindingRepositoryPort.class);
        SensitiveDataProtector protector = mock(SensitiveDataProtector.class);
        ContactBinding binding = binding(ownerUserId, contactId, ContactStatus.ACTIVE);
        when(repository.findByOwnerAndIdForUpdate(ownerUserId, contactId))
                .thenReturn(Optional.of(binding));
        ContactWechatActionProjectionService service =
                new ContactWechatActionProjectionService(repository, protector);

        Optional<WechatActionContactSnapshot> result = service.findVerifiedForUpdate(
                ownerUserId, contactId, "8.0.76", "locator-v1", true);

        assertTrue(result.isEmpty());
        verify(protector, never()).decrypt(binding.wechatLocatorCipher());
    }

    private ContactBinding binding(
            UUID ownerUserId,
            UUID contactId,
            ContactStatus status) {
        Instant now = Instant.parse("2026-08-19T08:00:00Z");
        return new ContactBinding(
                contactId, ownerUserId, new byte[32], new byte[] {1},
                new byte[] {2}, new byte[32], null,
                "8.0.56", "locator-v1", null, null, null, null,
                now.minusSeconds(60), "daughter", "consent-v1",
                now.minusSeconds(120), status, ownerUserId, 7,
                now.minusSeconds(120), now.minusSeconds(60), null);
    }
}
