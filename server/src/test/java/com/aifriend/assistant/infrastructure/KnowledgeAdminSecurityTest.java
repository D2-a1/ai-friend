package com.aifriend.assistant.infrastructure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.aifriend.identity.application.DeviceTrustService;
import com.aifriend.shared.security.ActiveAccountStatusPort;
import com.aifriend.shared.security.PublicIdCodec;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = KnowledgeAdminSecurityTest.Config.class)
class KnowledgeAdminSecurityTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ActiveAccountStatusPort accounts;
    @Autowired private DeviceTrustService devices;
    private MockMvc mvc;
    private final String subject = PublicIdCodec.userId(UUID.randomUUID());

    @BeforeEach
    void prepare() {
        reset(accounts, devices);
        when(accounts.isActive(any())).thenReturn(true);
        when(devices.isAllowedJwtDevice(any())).thenReturn(true);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void anonymousIsUnauthorized() throws Exception {
        mvc.perform(post("/admin/knowledge/imports")).andExpect(status().isUnauthorized());
    }
    @Test void ordinaryUserIsForbidden() throws Exception {
        mvc.perform(post("/admin/knowledge/imports").with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isForbidden());
    }
    @Test void exactScopeIsRequired() throws Exception {
        mvc.perform(post("/admin/knowledge/imports").with(jwt().jwt(token -> token.subject(subject))
                .authorities(new SimpleGrantedAuthority("SCOPE_knowledge:read"))))
                .andExpect(status().isForbidden());
    }
    @Test void explicitlyAuthorizedIdentityReachesOnlyTestHandler() throws Exception {
        mvc.perform(post("/admin/knowledge/imports").with(jwt().jwt(token -> token.subject(subject))
                .authorities(new SimpleGrantedAuthority(KnowledgeAdminSecurityConfiguration.AUTHORITY))))
                .andExpect(status().isOk());
    }
    @Test void closedAccountCannotUseManagementScope() throws Exception {
        when(accounts.isActive(any())).thenReturn(false);
        mvc.perform(post("/admin/knowledge/imports").with(jwt().jwt(token -> token.subject(subject))
                .authorities(new SimpleGrantedAuthority(KnowledgeAdminSecurityConfiguration.AUTHORITY))))
                .andExpect(status().isConflict());
    }
    @Test void untrustedDeviceCannotUseManagementScope() throws Exception {
        when(devices.isAllowedJwtDevice(any())).thenReturn(false);
        mvc.perform(post("/admin/knowledge/imports").with(jwt().jwt(token -> token.subject(subject))
                .authorities(new SimpleGrantedAuthority(KnowledgeAdminSecurityConfiguration.AUTHORITY))))
                .andExpect(status().isForbidden());
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import(KnowledgeAdminSecurityConfiguration.class)
    static class Config {
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean JwtDecoder decoder() { return mock(JwtDecoder.class); }
        @Bean ActiveAccountStatusPort accounts() { return mock(ActiveAccountStatusPort.class); }
        @Bean DeviceTrustService devices() { return mock(DeviceTrustService.class); }
        @Bean TestHandler handler() { return new TestHandler(); }
    }
    @RestController
    static class TestHandler {
        @PostMapping("/admin/knowledge/imports") String accept() { return "test-only"; }
    }
}
