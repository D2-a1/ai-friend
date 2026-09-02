package com.aifriend.invitation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InvitationWebControllerTest {

    @Test
    void shouldServeFixedNoStorePageWithoutInterpolatingInvitationId() throws IOException {
        InvitationWebController controller = new InvitationWebController();

        ResponseEntity<Resource> response = controller.invitation(
                "iv_0123456789abcdef0123456789abcdef");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("no-referrer", response.getHeaders().getFirst("Referrer-Policy"));
        String page = response.getBody().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(page.contains("iv_0123456789abcdef0123456789abcdef"));
        assertFalse(page.contains("localStorage"));
        assertFalse(page.contains("sessionStorage"));
        assertTrue(page.contains("id=\"wechat-id\""));
        assertTrue(page.contains("不要填写昵称或手机号"));
    }

    @Test
    void shouldKeepSecretsInMemoryAndClearAddressBarValues() throws IOException {
        ResponseEntity<Resource> response = new InvitationWebController().continuation();
        Resource script = response.getBody().createRelative("assets/invitation.js");

        String source = script.getContentAsString(StandardCharsets.UTF_8);

        assertTrue(source.contains("history.replaceState"));
        assertTrue(source.contains("let currentCsrfToken = null"));
        assertTrue(source.contains("consentPolicyVersion: currentPolicyVersion, wechatId"));
        assertTrue(source.contains("^[A-Za-z][A-Za-z0-9_-]{5,63}$"));
        assertFalse(source.contains("localStorage"));
        assertFalse(source.contains("sessionStorage"));
        assertFalse(source.contains("console."));
    }

    @Test
    void shouldApplyStrictBrowserSecurityHeaders() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/invite/continue");
        request.setContextPath("/api/v1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new InvitationWebHeadersFilter().doFilterInternal(
                request, response, new MockFilterChain());

        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertTrue(response.getHeader("Content-Security-Policy")
                .contains("frame-ancestors 'none'"));
        assertTrue(response.getHeader("Permissions-Policy").contains("microphone=()"));
        assertTrue(response.getHeader("Cache-Control").contains("no-store"));
    }

    @Test
    void shouldApplyNoStoreHeadersToWechatOAuthCallback() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/oauth/wechat/invitation-callback");
        request.setContextPath("/api/v1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new InvitationWebHeadersFilter().doFilterInternal(
                request, response, new MockFilterChain());

        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertTrue(response.getHeader("Cache-Control").contains("no-store"));
        assertTrue(response.getHeader("Pragma").contains("no-cache"));
    }
}
