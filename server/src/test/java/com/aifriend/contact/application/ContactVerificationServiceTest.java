package com.aifriend.contact.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aifriend.contact.domain.ContactBinding;
import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.contact.domain.WechatPageType;
import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.shared.security.SecurityKeyMaterial;
import com.aifriend.shared.security.SensitiveDataProtector;

class ContactVerificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-09T03:00:00Z");
    private static final String IDEMPOTENCY_KEY = "01JLOCALVERIFY00000000000001";

    private ContactBindingRepositoryPort repository;
    private AuditEventPort auditEventPort;
    private SensitiveDataProtector protector;
    private DigestService digestService;
    private ContactVerificationService service;

    @BeforeEach
    void setUp() {
        repository = mock(ContactBindingRepositoryPort.class);
        auditEventPort = mock(AuditEventPort.class);
        protector = protector();
        digestService = new DigestService();
        service = new ContactVerificationService(
                repository,
                protector,
                digestService,
                new ContactVerificationProperties(
                        Duration.ofMinutes(5),
                        Duration.ofSeconds(30),
                        List.of("8.0.56:wechat-contact-v1")),
                new ContactSummaryMapper(
                        protector,
                        new ContactAliasMapper(protector, mock(AcousticTemplatePort.class))),
                auditEventPort,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldProtectLocatorAndActivateWithoutAlias() {
        ContactBinding pending = pendingBinding();
        when(repository.findByOwnerAndIdForUpdate(pending.ownerUserId(), pending.id()))
                .thenReturn(Optional.of(pending));
        when(repository.save(any(ContactBinding.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ContactSummary result = service.verify(
                pending.ownerUserId(), PublicIdCodec.contactId(pending.id()),
                IDEMPOTENCY_KEY, validCommand());

        ArgumentCaptor<ContactBinding> captor = ArgumentCaptor.forClass(ContactBinding.class);
        verify(repository).lockOwner(pending.ownerUserId());
        verify(repository).save(captor.capture());
        ContactBinding saved = captor.getValue();
        assertEquals("wxid_stable_target", protector.decrypt(saved.wechatLocatorCipher()));
        assertEquals("二女儿", protector.decrypt(saved.remarkCipher()));
        assertEquals(ContactStatus.ACTIVE_NO_ALIAS, saved.status());
        assertEquals("8.0.56", saved.wechatVersion());
        assertEquals("wechat-contact-v1", saved.localVerificationVersion());
        assertNotNull(saved.wechatLocatorHash());
        assertNotNull(saved.verificationIdempotencyKeyHash());
        assertNotNull(saved.verificationRequestHash());
        assertEquals(NOW, saved.verifiedAt());
        assertEquals(ContactStatus.ACTIVE_NO_ALIAS, result.status());
        assertEquals("二女儿", result.remark());
        verify(auditEventPort).append(
                pending.ownerUserId(), "CONTACT_LOCAL_VERIFY", "SUCCESS", null, NOW);
    }

    @Test
    void shouldReplaySameKeyAndBodyEvenAfterEvidenceWindow() {
        LocalVerificationCommand command = new LocalVerificationCommand(
                "wxid_stable_target", "二女儿", WechatPageType.CONTACT_PROFILE,
                true, 1, true, "8.0.56", "wechat-contact-v1",
                NOW.minus(Duration.ofHours(1)), 1);
        ContactBinding replayed = verifiedBinding(
                digestService.sha256(IDEMPOTENCY_KEY),
                digestService.sha256(command.fingerprintInput()));
        when(repository.findByOwnerAndIdForUpdate(replayed.ownerUserId(), replayed.id()))
                .thenReturn(Optional.of(replayed));

        ContactSummary result = service.verify(
                replayed.ownerUserId(), PublicIdCodec.contactId(replayed.id()),
                IDEMPOTENCY_KEY, command);

        assertEquals(ContactStatus.ACTIVE_NO_ALIAS, result.status());
        verify(repository, never()).save(any(ContactBinding.class));
        verify(auditEventPort, never()).append(any(), any(), any(), any(), any());
    }

    @Test
    void shouldRejectSameKeyWithDifferentBody() {
        ContactBinding replayed = verifiedBinding(
                digestService.sha256(IDEMPOTENCY_KEY), new byte[32]);
        when(repository.findByOwnerAndIdForUpdate(replayed.ownerUserId(), replayed.id()))
                .thenReturn(Optional.of(replayed));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.verify(
                        replayed.ownerUserId(), PublicIdCodec.contactId(replayed.id()),
                        IDEMPOTENCY_KEY, validCommand()));

        assertEquals(ErrorCode.SESSION_CONFLICT, exception.errorCode());
    }

    @Test
    void shouldFailClosedForUnsupportedRuleOrWeakPageEvidence() {
        ContactBinding pending = pendingBinding();
        when(repository.findByOwnerAndIdForUpdate(pending.ownerUserId(), pending.id()))
                .thenReturn(Optional.of(pending));
        LocalVerificationCommand unsupportedRule = new LocalVerificationCommand(
                "wxid_stable_target", null, WechatPageType.CONTACT_PROFILE,
                true, 1, true, "8.0.57", "unknown-rule", NOW, 1);

        BusinessException ruleException = assertThrows(
                BusinessException.class,
                () -> service.verify(
                        pending.ownerUserId(), PublicIdCodec.contactId(pending.id()),
                        IDEMPOTENCY_KEY, unsupportedRule));
        assertEquals(ErrorCode.WECHAT_RULE_UNSUPPORTED, ruleException.errorCode());

        LocalVerificationCommand weakEvidence = new LocalVerificationCommand(
                "wxid_stable_target", null, WechatPageType.UNSUPPORTED,
                false, 1, false, "8.0.56", "wechat-contact-v1", NOW, 1);
        BusinessException pageException = assertThrows(
                BusinessException.class,
                () -> service.verify(
                        pending.ownerUserId(), PublicIdCodec.contactId(pending.id()),
                        "01JLOCALVERIFY00000000000002", weakEvidence));
        assertEquals(ErrorCode.WECHAT_PAGE_UNVERIFIED, pageException.errorCode());
    }

    @Test
    void shouldRejectLocatorAlreadyUsedByAnotherOwnerScopedContact() {
        ContactBinding pending = pendingBinding();
        when(repository.findByOwnerAndIdForUpdate(pending.ownerUserId(), pending.id()))
                .thenReturn(Optional.of(pending));
        when(repository.existsOtherByOwnerAndLocatorHash(
                org.mockito.ArgumentMatchers.eq(pending.ownerUserId()),
                org.mockito.ArgumentMatchers.eq(pending.id()),
                any(byte[].class))).thenReturn(true);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.verify(
                        pending.ownerUserId(), PublicIdCodec.contactId(pending.id()),
                        IDEMPOTENCY_KEY, validCommand()));

        assertEquals(ErrorCode.WECHAT_PAGE_UNVERIFIED, exception.errorCode());
        verify(repository, never()).save(any(ContactBinding.class));
    }

    private LocalVerificationCommand validCommand() {
        return new LocalVerificationCommand(
                "wxid_stable_target", "二女儿", WechatPageType.CONTACT_PROFILE,
                true, 1, true, "8.0.56", "wechat-contact-v1", NOW, 1);
    }

    private ContactBinding pendingBinding() {
        UUID ownerUserId = UUID.randomUUID();
        return new ContactBinding(
                UUID.randomUUID(), ownerUserId, new byte[] {1}, new byte[] {2},
                null, null, null, null, null, null, null, null, null, null,
                "女儿", "invitation-consent-v1", NOW.minus(Duration.ofDays(1)),
                ContactStatus.PENDING_LOCAL_VERIFY, ownerUserId, 0L,
                NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofDays(1)), null);
    }

    private ContactBinding verifiedBinding(byte[] idempotencyHash, byte[] requestHash) {
        UUID ownerUserId = UUID.randomUUID();
        return new ContactBinding(
                UUID.randomUUID(), ownerUserId, new byte[] {1}, new byte[] {2},
                protector.encrypt("wxid_stable_target"),
                protector.subjectHmac("wechat-locator-v1:wxid_stable_target"),
                protector.encrypt("二女儿"), "8.0.56", "wechat-contact-v1",
                idempotencyHash, requestHash, null, null, NOW, "女儿",
                "invitation-consent-v1", NOW.minus(Duration.ofDays(1)),
                ContactStatus.ACTIVE_NO_ALIAS, ownerUserId, 1L,
                NOW.minus(Duration.ofDays(1)), NOW, null);
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
