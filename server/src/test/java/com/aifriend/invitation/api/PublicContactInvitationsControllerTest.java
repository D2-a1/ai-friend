package com.aifriend.invitation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import com.aifriend.invitation.application.AcceptedInvitation;
import com.aifriend.invitation.application.InvitationAcceptanceService;
import com.aifriend.invitation.application.InvitationDecisionService;
import com.aifriend.invitation.application.InvitationSessionProperties;

class PublicContactInvitationsControllerTest {

    @Test
    void shouldDeclineAndClearHardenedHostCookieWithoutCaching() {
        InvitationDecisionService service = mock(InvitationDecisionService.class);
        InvitationAcceptanceService acceptanceService = mock(InvitationAcceptanceService.class);
        InvitationSessionProperties properties = new InvitationSessionProperties(
                "__Host-ai_friend_invitation", Duration.ofMinutes(30));
        PublicContactInvitationsController controller =
                new PublicContactInvitationsController(service, acceptanceService, properties);
        String sessionToken = "s".repeat(43);
        String csrfToken = "c".repeat(43);
        String idempotencyKey = "01JINVITATIONDECLINE000000001";

        ResponseEntity<Void> response = controller.declinePublicContactInvitation(
                sessionToken,
                csrfToken,
                idempotencyKey,
                new DeclineInvitationReq(true));

        verify(service).decline(sessionToken, csrfToken, idempotencyKey, true);
        assertEquals(204, response.getStatusCode().value());
        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertTrue(cookie.startsWith("__Host-ai_friend_invitation="));
        assertTrue(cookie.contains("Path=/"));
        assertTrue(cookie.contains("Max-Age=0"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Lax"));
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("no-cache", response.getHeaders().getPragma());
        assertEquals("no-referrer", response.getHeaders().getFirst("Referrer-Policy"));
    }

    @Test
    void shouldAcceptExplicitDecisionWithoutCaching() {
        InvitationDecisionService decisionService = mock(InvitationDecisionService.class);
        InvitationAcceptanceService acceptanceService = mock(InvitationAcceptanceService.class);
        InvitationSessionProperties properties = new InvitationSessionProperties(
                "__Host-ai_friend_invitation", Duration.ofMinutes(30));
        PublicContactInvitationsController controller =
                new PublicContactInvitationsController(
                        decisionService, acceptanceService, properties);
        String sessionToken = "s".repeat(43);
        String csrfToken = "c".repeat(43);
        String idempotencyKey = "01JINVITATIONACCEPT0000000001";
        String wechatId = "relative_123";
        when(acceptanceService.accept(
                sessionToken, csrfToken, idempotencyKey, true,
                "invitation-consent-v1", wechatId))
                .thenReturn(new AcceptedInvitation(
                        AcceptedInvitation.ACCEPTED_READY_FOR_ALIAS));

        ResponseEntity<com.aifriend.shared.api.ApiResponse<InvitationDecisionResp>> response =
                controller.acceptPublicContactInvitation(
                        sessionToken,
                        csrfToken,
                        idempotencyKey,
                        new AcceptInvitationReq(true, "invitation-consent-v1", wechatId));

        verify(acceptanceService).accept(
                sessionToken, csrfToken, idempotencyKey, true,
                "invitation-consent-v1", wechatId);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(AcceptedInvitation.ACCEPTED_READY_FOR_ALIAS,
                response.getBody().data().status());
        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertTrue(cookie.startsWith("__Host-ai_friend_invitation="));
        assertTrue(cookie.contains("Path=/"));
        assertTrue(cookie.contains("Max-Age=0"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Lax"));
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("no-cache", response.getHeaders().getPragma());
        assertEquals("no-referrer", response.getHeaders().getFirst("Referrer-Policy"));
    }
}
