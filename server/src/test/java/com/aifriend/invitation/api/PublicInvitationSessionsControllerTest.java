package com.aifriend.invitation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import com.aifriend.invitation.application.CreatedInvitationSession;
import com.aifriend.invitation.application.InvitationSessionProperties;
import com.aifriend.invitation.application.InvitationSessionService;
import com.aifriend.shared.api.ApiResponse;

class PublicInvitationSessionsControllerTest {

    @Test
    void shouldReturnHardenedHostCookieAndNoStoreHeaders() {
        InvitationSessionService service = mock(InvitationSessionService.class);
        InvitationSessionProperties properties = new InvitationSessionProperties(
                "__Host-ai_friend_invitation", Duration.ofMinutes(30));
        PublicInvitationSessionsController controller = new PublicInvitationSessionsController(service, properties);
        String invitationId = "iv_0123456789abcdef0123456789abcdef";
        String proof = "p".repeat(43);
        String sessionToken = "s".repeat(43);
        when(service.redeem(invitationId, proof)).thenReturn(new CreatedInvitationSession(
                sessionToken,
                "c".repeat(43),
                URI.create("https://invite.example.com/dev/wechat-authorization?state=redacted"),
                Instant.parse("2026-08-07T02:30:00Z")));

        ResponseEntity<ApiResponse<InvitationSessionCreatedResp>> response =
                controller.createPublicContactInvitationSession(
                        new CreateInvitationSessionReq(invitationId, proof));

        assertEquals(201, response.getStatusCode().value());
        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertTrue(cookie.startsWith("__Host-ai_friend_invitation=" + sessionToken));
        assertTrue(cookie.contains("Path=/"));
        assertTrue(cookie.contains("Max-Age=1800"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Lax"));
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("no-referrer", response.getHeaders().getFirst("Referrer-Policy"));
        assertFalse(response.getBody().toString().contains(sessionToken));
        assertEquals("CreateInvitationSessionReq[redacted]",
                new CreateInvitationSessionReq(invitationId, proof).toString());
    }
}
