package com.aifriend.contact.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.contact.domain.ContactBinding;
import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.contact.infrastructure.MfccDtwAcousticTemplateAdapter;
import com.aifriend.dialect.application.DialectPackageRegistry;
import com.aifriend.dialect.application.DialectPackageState;
import com.aifriend.dialect.application.VerifiedDialectPackage;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.shared.security.SecurityKeyMaterial;
import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.voice.application.AudioObjectConsumptionService;
import com.aifriend.voice.application.ValidatedAudioObject;
import com.aifriend.voice.domain.AudioPurpose;

class ContactAliasEnrollmentServiceTest {

    @Test
    void shouldFailClosedBeforeTransactionWhenCalibratedEngineIsUnavailable() {
        UUID ownerUserId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ContactBindingRepositoryPort bindingRepository = mock(ContactBindingRepositoryPort.class);
        ContactAliasRepositoryPort aliasRepository = mock(ContactAliasRepositoryPort.class);
        ConsentGrantQueryPort consentQuery = mock(ConsentGrantQueryPort.class);
        AudioObjectConsumptionService audioService = mock(AudioObjectConsumptionService.class);
        ContactAliasTransactionService transactionService = mock(ContactAliasTransactionService.class);
        SensitiveDataProtector protector = protector();
        MfccDtwAcousticTemplateAdapter acousticTemplateAdapter =
                new MfccDtwAcousticTemplateAdapter(unavailableRegistry());
        ContactAliasEnrollmentService service = new ContactAliasEnrollmentService(
                bindingRepository,
                aliasRepository,
                consentQuery,
                new DigestService(),
                audioService,
                acousticTemplateAdapter,
                transactionService,
                new ContactAliasMapper(protector, acousticTemplateAdapter));
        when(bindingRepository.findByOwnerAndId(ownerUserId, contactId))
                .thenReturn(Optional.of(activeBinding(ownerUserId, contactId)));
        when(consentQuery.isGranted(ownerUserId, ConsentType.VOICE_TEMPLATE)).thenReturn(true);
        when(audioService.validateAll(
                any(), any(), org.mockito.ArgumentMatchers.eq(AudioPurpose.ALIAS_ENROLLMENT)))
                .thenReturn(List.of(validated(ownerUserId), validated(ownerUserId)));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.create(
                        ownerUserId,
                        PublicIdCodec.contactId(contactId),
                        "01JALIASCREATE00000000000001",
                        new CreateContactAliasCommand(
                                "大女儿", null,
                                PublicIdCodec.audioObjectId(UUID.randomUUID()),
                                PublicIdCodec.audioObjectId(UUID.randomUUID()),
                                1, true)));

        assertEquals(ErrorCode.TEMPLATE_INCOMPATIBLE, exception.errorCode());
        verify(transactionService, never()).create(
                any(), any(), any(), any(), any(), any(), any());
    }

    private ContactBinding activeBinding(UUID ownerUserId, UUID contactId) {
        Instant now = Instant.parse("2026-08-12T04:00:00Z");
        return new ContactBinding(
                contactId, ownerUserId, new byte[] {1}, new byte[] {2},
                new byte[] {3}, new byte[] {4}, null,
                "8.0.56", "wechat-contact-v1", null, null, null, null,
                now, "女儿", "privacy-v1", now,
                ContactStatus.ACTIVE_NO_ALIAS, ownerUserId, 0,
                now.minusSeconds(60), now, null);
    }

    private ValidatedAudioObject validated(UUID ownerUserId) {
        return new ValidatedAudioObject(
                UUID.randomUUID(), ownerUserId, AudioPurpose.ALIAS_ENROLLMENT,
                "audio/wav", new byte[] {1, 2, 3}, 1_000,
                "sha256:test", 1);
    }

    private SensitiveDataProtector protector() {
        byte[] keyBytes = new byte[32];
        SecurityKeyMaterial keyMaterial = new SecurityKeyMaterial(
                new SecretKeySpec(keyBytes, "HmacSHA256"),
                new SecretKeySpec(keyBytes, "AES"),
                new SecretKeySpec(keyBytes, "HmacSHA256"));
        return new SensitiveDataProtector(keyMaterial);
    }

    private DialectPackageRegistry unavailableRegistry() {
        return new DialectPackageRegistry() {
            @Override
            public DialectPackageState state() {
                return DialectPackageState.DISABLED;
            }

            @Override
            public Optional<VerifiedDialectPackage> findActive() {
                return Optional.empty();
            }
        };
    }
}
