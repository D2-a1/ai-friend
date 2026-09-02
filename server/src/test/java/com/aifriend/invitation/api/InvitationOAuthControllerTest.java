package com.aifriend.invitation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import com.aifriend.invitation.application.InvitationOAuthService;
import com.aifriend.invitation.application.InvitationSessionProperties;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

class InvitationOAuthControllerTest {

    @Test
    void shouldRedirectSuccessfulCallbackToCleanContinuationPage() {
        InvitationOAuthService service = mock(InvitationOAuthService.class);
        InvitationOAuthController controller = controller(service);
        String sessionToken = "s".repeat(43);
        String state = "o".repeat(43);
        String code = "local_relative.12345678";

        ResponseEntity<Void> response = controller.completeWechatInvitationOAuth(
                sessionToken, code, state);

        verify(service).complete(sessionToken, state, code);
        assertEquals(303, response.getStatusCode().value());
        assertEquals("https://invite.example.com/invite/continue",
                response.getHeaders().getLocation().toString());
        assertPrivacyHeaders(response);
    }

    @Test
    void shouldRedirectFailureToSameOriginUnavailablePageWithoutSensitiveParameters() {
        InvitationOAuthService service = mock(InvitationOAuthService.class);
        InvitationOAuthController controller = controller(service);
        String state = "o".repeat(43);
        String code = "local_relative.12345678";
        doThrow(new BusinessException(ErrorCode.INVITATION_UNAVAILABLE))
                .when(service).complete(null, state, code);

        ResponseEntity<Void> response = controller.completeWechatInvitationOAuth(
                null, code, state);

        assertEquals(303, response.getStatusCode().value());
        assertEquals("https://invite.example.com/invite/unavailable",
                response.getHeaders().getLocation().toString());
        assertPrivacyHeaders(response);
    }

    private InvitationOAuthController controller(InvitationOAuthService service) {
        InvitationSessionProperties properties = new InvitationSessionProperties(
                "__Host-ai_friend_invitation", Duration.ofMinutes(30));
        return new InvitationOAuthController(service, properties);
    }

    private void assertPrivacyHeaders(ResponseEntity<Void> response) {
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("no-cache", response.getHeaders().getPragma());
        assertEquals("no-referrer", response.getHeaders().getFirst("Referrer-Policy"));
        assertEquals(null, response.getHeaders().getFirst(HttpHeaders.REFERER));
    }
}
