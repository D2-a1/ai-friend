package com.aifriend.contact.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
    void locallyVerifiedContactUsesIndependentInvitationLocatorVersion() {
        UUID ownerUserId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ContactBindingRepositoryPort repository = mock(ContactBindingRepositoryPort.class);
        SensitiveDataProtector protector = mock(SensitiveDataProtector.class);
        ContactBinding binding = binding(ownerUserId, contactId, ContactStatus.ACTIVE);
        when(repository.findByOwnerAndIdForUpdate(ownerUserId, contactId))
                .thenReturn(Optional.of(binding));
        when(protector.decrypt(binding.wechatLocatorCipher()))
                .thenReturn("private-stable-locator");
        when(protector.subjectHmac(
                WechatLocatorPolicy.HMAC_DOMAIN + "private-stable-locator"))
                .thenReturn(binding.wechatLocatorHash());
        ContactWechatActionProjectionService service =
                new ContactWechatActionProjectionService(repository, protector);

        Optional<WechatActionContactSnapshot> result = service.findVerifiedForUpdate(
                ownerUserId, contactId,
                WechatLocatorPolicy.INVITATION_WECHAT_ID_VERSION);

        assertTrue(result.isPresent());
        assertFalse(result.orElseThrow().toString().contains("private-stable-locator"));
        assertEquals("8.0.56", result.orElseThrow().wechatVersion());
        assertEquals(WechatLocatorPolicy.INVITATION_WECHAT_ID_VERSION,
                result.orElseThrow().locatorVersion());
        verify(repository).findByOwnerAndIdForUpdate(ownerUserId, contactId);
    }

    @Test
    void inactiveUnsupportedLocatorContractOrBrokenCipherReturnsNoProjection() {
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
                ownerUserId, contactId,
                WechatLocatorPolicy.INVITATION_WECHAT_ID_VERSION).isEmpty());

        ContactBinding active = binding(ownerUserId, contactId, ContactStatus.ACTIVE);
        when(repository.findByOwnerAndIdForUpdate(ownerUserId, contactId))
                .thenReturn(Optional.of(active));
        assertTrue(service.findVerifiedForUpdate(
                ownerUserId, contactId, "unsupported-locator-v2")
                .isEmpty());

        when(protector.decrypt(active.wechatLocatorCipher()))
                .thenThrow(new IllegalStateException("test cipher failure"));
        assertTrue(service.findVerifiedForUpdate(
                ownerUserId, contactId,
                WechatLocatorPolicy.INVITATION_WECHAT_ID_VERSION).isEmpty());
    }

    @Test
    void locatorCipherAndHashMismatchReturnsNoProjection() {
        UUID ownerUserId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ContactBindingRepositoryPort repository = mock(ContactBindingRepositoryPort.class);
        SensitiveDataProtector protector = mock(SensitiveDataProtector.class);
        ContactBinding binding = binding(ownerUserId, contactId, ContactStatus.ACTIVE);
        when(repository.findByOwnerAndIdForUpdate(ownerUserId, contactId))
                .thenReturn(Optional.of(binding));
        when(protector.decrypt(binding.wechatLocatorCipher()))
                .thenReturn("private-stable-locator");
        when(protector.subjectHmac(
                WechatLocatorPolicy.HMAC_DOMAIN + "private-stable-locator"))
                .thenReturn(new byte[] {9});
        ContactWechatActionProjectionService service =
                new ContactWechatActionProjectionService(repository, protector);

        Optional<WechatActionContactSnapshot> result = service.findVerifiedForUpdate(
                ownerUserId, contactId,
                WechatLocatorPolicy.INVITATION_WECHAT_ID_VERSION);

        assertTrue(result.isEmpty());
    }

    @Test
    void invitationContactWithoutHistoricalWechatVersionMustRemainExecutable() {
        UUID ownerUserId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ContactBindingRepositoryPort repository = mock(ContactBindingRepositoryPort.class);
        SensitiveDataProtector protector = mock(SensitiveDataProtector.class);
        ContactBinding binding = invitationBinding(
                ownerUserId, contactId, ContactStatus.ACTIVE);
        when(repository.findByOwnerAndIdForUpdate(ownerUserId, contactId))
                .thenReturn(Optional.of(binding));
        when(protector.decrypt(binding.wechatLocatorCipher()))
                .thenReturn("private-stable-locator");
        when(protector.subjectHmac(
                WechatLocatorPolicy.HMAC_DOMAIN + "private-stable-locator"))
                .thenReturn(binding.wechatLocatorHash());
        ContactWechatActionProjectionService service =
                new ContactWechatActionProjectionService(repository, protector);

        Optional<WechatActionContactSnapshot> result = service.findVerifiedForUpdate(
                ownerUserId, contactId,
                WechatLocatorPolicy.INVITATION_WECHAT_ID_VERSION);

        assertTrue(result.isPresent());
        assertEquals(null, result.orElseThrow().wechatVersion());
    }

    private ContactBinding binding(
            UUID ownerUserId,
            UUID contactId,
            ContactStatus status) {
        Instant now = Instant.parse("2026-08-19T08:00:00Z");
        return new ContactBinding(
                contactId, ownerUserId, new byte[32], new byte[] {1},
                new byte[] {2}, new byte[32], null,
                "8.0.56", "wechat-contact-profile-v1", null, null, null, null,
                now.minusSeconds(60), "daughter", "consent-v1",
                now.minusSeconds(120), status, ownerUserId, 7,
                now.minusSeconds(120), now.minusSeconds(60), null);
    }

    private ContactBinding invitationBinding(
            UUID ownerUserId,
            UUID contactId,
            ContactStatus status) {
        Instant now = Instant.parse("2026-08-19T08:00:00Z");
        return new ContactBinding(
                contactId, ownerUserId, new byte[32], new byte[] {1},
                new byte[] {2}, new byte[32], null,
                null, WechatLocatorPolicy.INVITATION_WECHAT_ID_VERSION,
                null, null, null, null,
                now.minusSeconds(60), "daughter", "consent-v1",
                now.minusSeconds(120), status, ownerUserId, 7,
                now.minusSeconds(120), now.minusSeconds(60), null);
    }
}
