package com.aifriend.contact.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aifriend.contact.domain.ContactAlias;
import com.aifriend.contact.domain.ContactAliasStatus;
import com.aifriend.contact.domain.ContactBinding;
import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.shared.security.SecurityKeyMaterial;
import com.aifriend.shared.security.SensitiveDataProtector;

class ContactAliasDeleteServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T06:00:00Z");

    @Test
    void shouldEraseLastAliasAndMoveContactToActiveNoAlias() {
        UUID ownerUserId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID aliasId = UUID.randomUUID();
        AliasNamespaceRepositoryPort namespaceRepository = mock(AliasNamespaceRepositoryPort.class);
        ContactBindingRepositoryPort bindingRepository = mock(ContactBindingRepositoryPort.class);
        ContactAliasRepositoryPort aliasRepository = mock(ContactAliasRepositoryPort.class);
        ContactAliasOutboxPort outboxPort = mock(ContactAliasOutboxPort.class);
        AuditEventPort auditPort = mock(AuditEventPort.class);
        DigestService digestService = new DigestService();
        SensitiveDataProtector protector = protector();
        ContactAliasDeleteService service = new ContactAliasDeleteService(
                namespaceRepository,
                bindingRepository,
                aliasRepository,
                digestService,
                new ContactSummaryMapper(
                        protector,
                        new ContactAliasMapper(protector, mock(AcousticTemplatePort.class))),
                outboxPort,
                auditPort,
                Clock.fixed(NOW, ZoneOffset.UTC));
        ContactBinding binding = activeBinding(ownerUserId, contactId);
        ContactAlias alias = activeAlias(ownerUserId, contactId, aliasId, protector);
        when(namespaceRepository.lock(ownerUserId)).thenReturn(4L);
        when(bindingRepository.findByOwnerAndIdForUpdate(ownerUserId, contactId))
                .thenReturn(Optional.of(binding));
        when(aliasRepository.findByOwnerAndBindingAndIdForUpdate(
                ownerUserId, contactId, aliasId)).thenReturn(Optional.of(alias));
        when(aliasRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(aliasRepository.countActiveByBinding(ownerUserId, contactId)).thenReturn(0L);
        when(aliasRepository.findActiveByOwner(ownerUserId)).thenReturn(java.util.List.of());
        when(bindingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ContactSummary result = service.delete(
                ownerUserId,
                PublicIdCodec.contactId(contactId),
                PublicIdCodec.aliasId(aliasId),
                "01JALIASDELETE00000000000001",
                new DeleteContactAliasCommand(true, 1));

        assertEquals(ContactStatus.ACTIVE_NO_ALIAS, result.status());
        assertEquals(0, result.aliasCount());
        ArgumentCaptor<ContactAlias> aliasCaptor = ArgumentCaptor.forClass(ContactAlias.class);
        verify(aliasRepository).save(aliasCaptor.capture());
        ContactAlias deleted = aliasCaptor.getValue();
        assertEquals(ContactAliasStatus.DELETED, deleted.status());
        assertNull(deleted.displayTextCipher());
        assertNull(deleted.phoneticHintCipher());
        assertNull(deleted.templateCipher());
        assertNull(deleted.templateDigest());
        verify(namespaceRepository).increment(ownerUserId, 4L, NOW);
        verify(outboxPort).appendDeleted(aliasId, contactId, ownerUserId, NOW);
        verify(auditPort).append(ownerUserId, "CONTACT_ALIAS_DELETE", "SUCCESS", null, NOW);
    }

    private ContactBinding activeBinding(UUID ownerUserId, UUID contactId) {
        return new ContactBinding(
                contactId, ownerUserId, new byte[] {1}, new byte[] {2},
                new byte[] {3}, new byte[] {4}, null,
                "8.0.56", "wechat-contact-v1", null, null, null, null,
                NOW.minusSeconds(120), "女儿", "privacy-v1", NOW.minusSeconds(120),
                ContactStatus.ACTIVE, ownerUserId, 0,
                NOW.minusSeconds(300), NOW.minusSeconds(120), null);
    }

    private ContactAlias activeAlias(
            UUID ownerUserId,
            UUID contactId,
            UUID aliasId,
            SensitiveDataProtector protector) {
        return new ContactAlias(
                aliasId, ownerUserId, contactId,
                protector.encrypt("大女儿"), protector.encrypt("da nv er"),
                "zh-Hans-CN-x-wugang", "wugang-test-v1",
                "content-template-test-v1", "registration-threshold-test-v1",
                protector.encryptBytes(new byte[] {1, 2, 3}), new byte[32],
                ContactAliasStatus.ACTIVE, new byte[32], new byte[32],
                null, null, 0, NOW.minusSeconds(60), NOW.minusSeconds(60), null);
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
