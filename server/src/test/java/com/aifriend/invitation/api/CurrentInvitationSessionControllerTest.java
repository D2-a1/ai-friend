package com.aifriend.invitation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.aifriend.invitation.application.InvitationAcceptanceService;
import com.aifriend.invitation.application.InvitationSessionView;
import com.aifriend.shared.api.ApiResponse;

class CurrentInvitationSessionControllerTest {

    @Test
    void shouldReturnRotatedCsrfTokenWithoutCaching() {
        InvitationAcceptanceService service = mock(InvitationAcceptanceService.class);
        String sessionToken = "s".repeat(43);
        String csrfToken = "r".repeat(43);
        when(service.view(sessionToken)).thenReturn(new InvitationSessionView(
                null,
                "将你添加为已绑定亲友",
                "invitation-consent-v1",
                Instant.parse("2026-08-09T03:30:00Z"),
                true,
                csrfToken));
        CurrentInvitationSessionController controller =
                new CurrentInvitationSessionController(service);

        ResponseEntity<ApiResponse<InvitationSessionViewResp>> response =
                controller.getCurrentPublicContactInvitationSession(sessionToken);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(csrfToken, response.getBody().data().csrfToken());
        assertEquals("InvitationSessionViewResp[redacted]",
                response.getBody().data().toString());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("no-cache", response.getHeaders().getPragma());
        assertEquals("no-referrer", response.getHeaders().getFirst("Referrer-Policy"));
    }
}
