package com.aifriend.contact.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.aifriend.contact.domain.ContactBinding;
import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.shared.security.SecurityKeyMaterial;
import com.aifriend.shared.security.SensitiveDataProtector;

class ContactQueryServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-06T01:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-07T01:00:00Z");

    @Test
    void shouldQueryOnlyRequestedOwnerAndExposeMinimumDisplayFields() {
        ContactBindingRepositoryPort repository = mock(ContactBindingRepositoryPort.class);
        ContactAliasRepositoryPort aliasRepository = mock(ContactAliasRepositoryPort.class);
        SensitiveDataProtector protector = protector();
        ContactQueryService service = new ContactQueryService(
                repository, aliasRepository,
                new ContactSummaryMapper(
                        protector,
                        new ContactAliasMapper(protector, mock(AcousticTemplatePort.class))));
        UUID ownerUserId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ContactBinding binding = new ContactBinding(
                contactId,
                ownerUserId,
                new byte[] {1},
                new byte[] {2},
                new byte[] {3},
                new byte[] {4},
                protector.encrypt("二女儿"),
                "wechat-locator-v1",
                UPDATED_AT,
                "女儿",
                ContactStatus.ACTIVE_NO_ALIAS,
                ownerUserId,
                0,
                CREATED_AT,
                UPDATED_AT,
                null);
        when(repository.findByOwner(ownerUserId, ContactStatus.ACTIVE_NO_ALIAS, 1, 10))
                .thenReturn(new ContactBindingPage(List.of(binding), 1, 10, 11, 2));

        ContactSummaryPage result = service.list(
                ownerUserId, ContactStatus.ACTIVE_NO_ALIAS, 1, 10);

        verify(repository).findByOwner(ownerUserId, ContactStatus.ACTIVE_NO_ALIAS, 1, 10);
        assertEquals(1, result.items().size());
        assertEquals(11, result.totalElements());
        ContactSummary summary = result.items().get(0);
        assertEquals(PublicIdCodec.contactId(contactId), summary.id());
        assertEquals("二女儿", summary.remark());
        assertEquals("女儿", summary.relationship());
        assertEquals(ContactStatus.ACTIVE_NO_ALIAS, summary.status());
        assertEquals(0, summary.aliasCount());
        assertEquals(1, summary.version());
        assertNull(summary.displayName());
        assertNull(summary.avatarUrl());
    }

    @Test
    void shouldRejectInvalidPagingBeforeRepositoryAccess() {
        ContactBindingRepositoryPort repository = mock(ContactBindingRepositoryPort.class);
        ContactAliasRepositoryPort aliasRepository = mock(ContactAliasRepositoryPort.class);
        ContactQueryService service = new ContactQueryService(
                repository, aliasRepository, summaryMapper());
        UUID ownerUserId = UUID.randomUUID();

        BusinessException negativePage = assertThrows(
                BusinessException.class,
                () -> service.list(ownerUserId, null, -1, 20));
        BusinessException oversizedPage = assertThrows(
                BusinessException.class,
                () -> service.list(ownerUserId, null, 0, 21));

        assertEquals(ErrorCode.VALIDATION_FAILED, negativePage.errorCode());
        assertEquals(ErrorCode.VALIDATION_FAILED, oversizedPage.errorCode());
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

    private ContactSummaryMapper summaryMapper() {
        SensitiveDataProtector protector = protector();
        return new ContactSummaryMapper(
                protector,
                new ContactAliasMapper(protector, mock(AcousticTemplatePort.class)));
    }
}
