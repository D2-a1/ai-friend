package com.aifriend.contact.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.contact.domain.ContactAlias;
import com.aifriend.contact.domain.ContactAliasStatus;
import com.aifriend.contact.domain.ContactBinding;
import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.SecurityKeyMaterial;
import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.voice.application.AudioObjectBatchConsumer;
import com.aifriend.voice.application.AudioObjectConsumptionTransactionService;
import com.aifriend.voice.application.ValidatedAudioObject;
import com.aifriend.voice.domain.AudioPurpose;

class ContactAliasTransactionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T05:00:00Z");

    @Test
    @SuppressWarnings("unchecked")
    void shouldCreateAliasAndAdvanceContactInsideAudioConsumptionCallback() {
        UUID ownerUserId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        AliasNamespaceRepositoryPort namespaceRepository = mock(AliasNamespaceRepositoryPort.class);
        ContactBindingRepositoryPort bindingRepository = mock(ContactBindingRepositoryPort.class);
        ContactAliasRepositoryPort aliasRepository = mock(ContactAliasRepositoryPort.class);
        ConsentGrantQueryPort consentQuery = mock(ConsentGrantQueryPort.class);
        AudioObjectConsumptionTransactionService audioTransaction =
                mock(AudioObjectConsumptionTransactionService.class);
        AcousticTemplatePort acousticPort = mock(AcousticTemplatePort.class);
        ContactAliasOutboxPort outboxPort = mock(ContactAliasOutboxPort.class);
        AuditEventPort auditPort = mock(AuditEventPort.class);
        SensitiveDataProtector protector = protector();
        DigestService digestService = new DigestService();
        ContactAliasMapper aliasMapper = new ContactAliasMapper(protector, acousticPort);
        ContactAliasTransactionService service = new ContactAliasTransactionService(
                namespaceRepository, bindingRepository, aliasRepository, consentQuery,
                audioTransaction, acousticPort, protector, digestService, aliasMapper,
                outboxPort, auditPort, Clock.fixed(NOW, ZoneOffset.UTC));
        ContactBinding binding = activeBinding(ownerUserId, contactId);
        List<ValidatedAudioObject> audioObjects = List.of(
                validated(ownerUserId), validated(ownerUserId));
        AcousticEnrollmentCandidate candidate = new AcousticEnrollmentCandidate(
                new byte[] {9, 8, 7}, "zh-Hans-CN-x-wugang", "wugang-test-v1",
                "content-template-test-v1", "registration-threshold-test-v1");
        byte[] idempotencyHash = digestService.sha256("create-key");
        byte[] requestHash = digestService.sha256("request");
        when(namespaceRepository.lock(ownerUserId)).thenReturn(7L);
        when(aliasRepository.findByOwnerAndCreateKeyForUpdate(ownerUserId, idempotencyHash))
                .thenReturn(Optional.empty());
        when(bindingRepository.findByOwnerAndIdForUpdate(ownerUserId, contactId))
                .thenReturn(Optional.of(binding));
        when(consentQuery.isGranted(ownerUserId, ConsentType.VOICE_TEMPLATE)).thenReturn(true);
        when(acousticPort.isCompatible(any(), any(), any(), any())).thenReturn(true);
        when(aliasRepository.findActiveByOwner(ownerUserId)).thenReturn(List.of());
        when(acousticPort.classify(candidate, List.of())).thenReturn(AcousticUniqueness.DISTINCT);
        when(aliasRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(bindingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(audioTransaction.consumeAll(any(), any())).thenAnswer(invocation -> {
            List<ValidatedAudioObject> values = invocation.getArgument(0);
            AudioObjectBatchConsumer<ContactAliasSummary> consumer = invocation.getArgument(1);
            return consumer.consume(values);
        });

        ContactAliasSummary result = service.create(
                ownerUserId, contactId, idempotencyHash, requestHash,
                new CreateContactAliasCommand(
                        "大女儿", null, "au_first", "au_second", 1, true),
                audioObjects, candidate);

        assertEquals("大女儿", result.displayText());
        assertEquals("COMPATIBLE", result.compatibility());
        ArgumentCaptor<ContactAlias> aliasCaptor = ArgumentCaptor.forClass(ContactAlias.class);
        verify(aliasRepository).save(aliasCaptor.capture());
        assertEquals(ContactAliasStatus.ACTIVE, aliasCaptor.getValue().status());
        assertEquals("大女儿", protector.decrypt(aliasCaptor.getValue().displayTextCipher()));
        ArgumentCaptor<ContactBinding> bindingCaptor = ArgumentCaptor.forClass(ContactBinding.class);
        verify(bindingRepository).save(bindingCaptor.capture());
        assertEquals(ContactStatus.ACTIVE, bindingCaptor.getValue().status());
        verify(namespaceRepository).increment(ownerUserId, 7L, NOW);
        verify(outboxPort).appendCreated(
                aliasCaptor.getValue().id(), contactId, ownerUserId, NOW);
        verify(auditPort).append(ownerUserId, "CONTACT_ALIAS_CREATE", "SUCCESS", null, NOW);
    }

    private ContactBinding activeBinding(UUID ownerUserId, UUID contactId) {
        return new ContactBinding(
                contactId, ownerUserId, new byte[] {1}, new byte[] {2},
                new byte[] {3}, new byte[] {4}, null,
                "8.0.56", "wechat-contact-v1", null, null, null, null,
                NOW, "女儿", "privacy-v1", NOW,
                ContactStatus.ACTIVE_NO_ALIAS, ownerUserId, 0,
                NOW.minusSeconds(60), NOW, null);
    }

    private ValidatedAudioObject validated(UUID ownerUserId) {
        return new ValidatedAudioObject(
                UUID.randomUUID(), ownerUserId, AudioPurpose.ALIAS_ENROLLMENT,
                "audio/wav", new byte[] {1, 2, 3}, 1_000,
                "sha256:test", 1);
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
