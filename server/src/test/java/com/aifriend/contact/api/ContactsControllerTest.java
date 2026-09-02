package com.aifriend.contact.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import com.aifriend.contact.application.ContactQueryService;
import com.aifriend.contact.application.ContactAliasDeleteService;
import com.aifriend.contact.application.ContactAliasEnrollmentService;
import com.aifriend.contact.application.ContactSummary;
import com.aifriend.contact.application.ContactSummaryPage;
import com.aifriend.contact.application.ContactUnbindCommand;
import com.aifriend.contact.application.ContactUnbindService;
import com.aifriend.contact.application.ContactVerificationService;
import com.aifriend.contact.application.LocalVerificationCommand;
import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.contact.domain.WechatPageType;
import com.aifriend.shared.api.ApiResponse;
import com.aifriend.shared.security.PublicIdCodec;

class ContactsControllerTest {

    @Test
    void shouldUseAuthenticatedOwnerAndReturnContractShape() {
        UUID ownerUserId = UUID.randomUUID();
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(PublicIdCodec.userId(ownerUserId));
        ContactQueryService service = mock(ContactQueryService.class);
        ContactSummary summary = new ContactSummary(
                PublicIdCodec.contactId(UUID.randomUUID()),
                null,
                "大儿子",
                null,
                "儿子",
                ContactStatus.PENDING_LOCAL_VERIFY,
                0,
                null,
                null,
                1,
                Instant.parse("2026-08-06T01:00:00Z"),
                Instant.parse("2026-08-07T01:00:00Z"));
        when(service.list(ownerUserId, ContactStatus.PENDING_LOCAL_VERIFY, 0, 20))
                .thenReturn(new ContactSummaryPage(List.of(summary), 0, 20, 1, 1));
        ContactsController controller = new ContactsController(
                service, mock(ContactVerificationService.class),
                mock(ContactUnbindService.class),
                mock(ContactAliasEnrollmentService.class),
                mock(ContactAliasDeleteService.class));

        ApiResponse<ContactPageResp> response = controller.listContacts(
                jwt, 0, 20, ContactStatus.PENDING_LOCAL_VERIFY);

        verify(service).list(ownerUserId, ContactStatus.PENDING_LOCAL_VERIFY, 0, 20);
        assertEquals("OK", response.code());
        assertEquals(1, response.data().items().size());
        ContactResp item = response.data().items().get(0);
        assertEquals("大儿子", item.remark());
        assertEquals(ContactStatus.PENDING_LOCAL_VERIFY, item.status());
        assertEquals(0, item.aliasCount());
        assertEquals(List.of(), item.aliases());
        assertNull(item.displayName());
        assertNull(item.avatarUrl());
        assertEquals(20, response.data().page().size());
        assertEquals(1, response.data().page().totalElements());
    }

    @Test
    void shouldUseAuthenticatedOwnerForLocalVerification() {
        UUID ownerUserId = UUID.randomUUID();
        String contactId = PublicIdCodec.contactId(UUID.randomUUID());
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(PublicIdCodec.userId(ownerUserId));
        ContactQueryService queryService = mock(ContactQueryService.class);
        ContactVerificationService verificationService = mock(ContactVerificationService.class);
        Instant verifiedAt = Instant.parse("2026-08-09T03:00:00Z");
        ContactSummary summary = new ContactSummary(
                contactId, null, "二女儿", null, "女儿",
                ContactStatus.ACTIVE_NO_ALIAS, 0, "wechat-contact-v1", verifiedAt,
                2, verifiedAt.minusSeconds(60), verifiedAt);
        when(verificationService.verify(
                org.mockito.ArgumentMatchers.eq(ownerUserId),
                org.mockito.ArgumentMatchers.eq(contactId),
                org.mockito.ArgumentMatchers.eq("01JLOCALVERIFY00000000000001"),
                any(LocalVerificationCommand.class))).thenReturn(summary);
        ContactsController controller = new ContactsController(
                queryService, verificationService, mock(ContactUnbindService.class),
                mock(ContactAliasEnrollmentService.class),
                mock(ContactAliasDeleteService.class));
        LocalVerificationReq request = new LocalVerificationReq(
                "wxid_stable_target", "二女儿", WechatPageType.CONTACT_PROFILE,
                true, 1, true, "8.0.56", "wechat-contact-v1", verifiedAt, 1);

        ApiResponse<ContactResp> response = controller.verifyLocalWechatContact(
                jwt, contactId, "01JLOCALVERIFY00000000000001", request);

        assertEquals(ContactStatus.ACTIVE_NO_ALIAS, response.data().status());
        assertEquals("二女儿", response.data().remark());
        verify(verificationService).verify(
                org.mockito.ArgumentMatchers.eq(ownerUserId),
                org.mockito.ArgumentMatchers.eq(contactId),
                org.mockito.ArgumentMatchers.eq("01JLOCALVERIFY00000000000001"),
                any(LocalVerificationCommand.class));
    }

    @Test
    void shouldUseAuthenticatedOwnerForUnbind() {
        UUID ownerUserId = UUID.randomUUID();
        String contactId = PublicIdCodec.contactId(UUID.randomUUID());
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(PublicIdCodec.userId(ownerUserId));
        ContactUnbindService unbindService = mock(ContactUnbindService.class);
        Instant now = Instant.parse("2026-08-09T05:00:00Z");
        ContactSummary summary = new ContactSummary(
                contactId, null, null, null, null,
                ContactStatus.REVOKED, 0, null, null,
                3, now.minusSeconds(120), now);
        when(unbindService.unbind(
                org.mockito.ArgumentMatchers.eq(ownerUserId),
                org.mockito.ArgumentMatchers.eq(contactId),
                org.mockito.ArgumentMatchers.eq("01JCONTACTUNBIND000000000001"),
                any(ContactUnbindCommand.class))).thenReturn(summary);
        ContactsController controller = new ContactsController(
                mock(ContactQueryService.class), mock(ContactVerificationService.class),
                unbindService, mock(ContactAliasEnrollmentService.class),
                mock(ContactAliasDeleteService.class));

        ApiResponse<ContactResp> response = controller.unbindContact(
                jwt, contactId, "01JCONTACTUNBIND000000000001",
                new ContactUnbindReq(true, 2));

        assertEquals(ContactStatus.REVOKED, response.data().status());
        assertNull(response.data().remark());
        verify(unbindService).unbind(
                org.mockito.ArgumentMatchers.eq(ownerUserId),
                org.mockito.ArgumentMatchers.eq(contactId),
                org.mockito.ArgumentMatchers.eq("01JCONTACTUNBIND000000000001"),
                any(ContactUnbindCommand.class));
    }
}
