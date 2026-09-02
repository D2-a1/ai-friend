package com.aifriend.shared.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import jakarta.servlet.FilterChain;

import com.aifriend.identity.application.DeviceTrustService;

class ActiveAccountFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldRejectOldAccessTokenAfterClosureAcceptance() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        ActiveAccountStatusPort statusPort = mock(ActiveAccountStatusPort.class);
        DeviceTrustService deviceTrustService = mock(DeviceTrustService.class);
        when(statusPort.isActive(ownerUserId)).thenReturn(false);
        when(deviceTrustService.isAllowedJwtDevice(null)).thenReturn(true);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = authenticatedRequest(ownerUserId, "GET", "/me/contacts");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ActiveAccountFilter(statusPort, deviceTrustService, new ObjectMapper())
                .doFilter(request, response, chain);

        assertEquals(409, response.getStatus());
        assertTrue(response.getContentAsString().contains("ACCOUNT_CLOSURE_ACCEPTED"));
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void shouldAllowAccountClosureReplayToReachIdempotencyTransaction() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        ActiveAccountStatusPort statusPort = mock(ActiveAccountStatusPort.class);
        DeviceTrustService deviceTrustService = mock(DeviceTrustService.class);
        when(deviceTrustService.isAllowedJwtDevice(null)).thenReturn(true);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = authenticatedRequest(ownerUserId, "DELETE", "/me/account");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ActiveAccountFilter(statusPort, deviceTrustService, new ObjectMapper())
                .doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(statusPort, never()).isActive(ownerUserId);
    }

    @Test
    void shouldRejectJwtWhenDeviceWasRemovedFromAllowlist() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        ActiveAccountStatusPort statusPort = mock(ActiveAccountStatusPort.class);
        DeviceTrustService deviceTrustService = mock(DeviceTrustService.class);
        when(statusPort.isActive(ownerUserId)).thenReturn(true);
        when(deviceTrustService.isAllowedJwtDevice(null)).thenReturn(false);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = authenticatedRequest(ownerUserId, "GET", "/me/contacts");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ActiveAccountFilter(statusPort, deviceTrustService, new ObjectMapper())
                .doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("DEVICE_NOT_ALLOWED"));
        verify(chain, never()).doFilter(request, response);
    }

    private MockHttpServletRequest authenticatedRequest(
            UUID ownerUserId,
            String method,
            String requestUri) {
        Jwt jwt = new Jwt(
                "token",
                Instant.parse("2026-08-20T01:00:00Z"),
                Instant.parse("2026-08-20T03:00:00Z"),
                Map.of("alg", "none"),
                Map.of("sub", PublicIdCodec.userId(ownerUserId)));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        MockHttpServletRequest request = new MockHttpServletRequest(method, requestUri);
        request.setRequestURI(requestUri);
        return request;
    }
}
