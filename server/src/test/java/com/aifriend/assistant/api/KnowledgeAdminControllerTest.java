package com.aifriend.assistant.api;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import com.aifriend.assistant.infrastructure.KnowledgeAdminSecurityConfiguration;
import com.aifriend.identity.application.DeviceTrustService;
import com.aifriend.shared.api.GlobalExceptionHandler;
import com.aifriend.shared.security.*;
import com.aifriend.retrieval.application.*;
import com.aifriend.retrieval.application.KnowledgeImportRegistrationPort.*;
import com.aifriend.retrieval.domain.*;

/** 真实Spring管理安全链与HTTP入口；新增Bearer用例使用生产JWT验签器，账号/设备/存储仍模拟。 */
@ExtendWith(SpringExtension.class) @WebAppConfiguration
@ContextConfiguration(classes=KnowledgeAdminControllerTest.Config.class)
@TestPropertySource(properties={"ai-friend.knowledge.enabled=true","ai-friend.knowledge.import.enabled=true"})
class KnowledgeAdminControllerTest {
    @Autowired WebApplicationContext context;
    @Autowired KnowledgeImportRegistrationPort registration;
    @Autowired KnowledgeDocumentManagementPort documents;
    @Autowired KnowledgeCleanupStatusPort cleanupStatus;
    @Autowired ActiveAccountStatusPort accounts;
    @Autowired DeviceTrustService devices;
    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;
    @Autowired IdentitySecurityProperties identityProperties;
    private MockMvc mvc;
    private final UUID job=UUID.randomUUID(),document=UUID.randomUUID();
    private final String subject=PublicIdCodec.userId(UUID.randomUUID());
    @BeforeEach void setup() {
        reset(registration,documents,accounts,devices,cleanupStatus);
        when(accounts.isActive(any())).thenReturn(true); when(devices.isAllowedJwtDevice(any())).thenReturn(true);
        var receipt=new Receipt(job,document,2,KnowledgeImportJob.State.PENDING,0,KnowledgeImportJob.Failure.NONE);
        when(registration.register(any(),any())).thenReturn(receipt);when(registration.find(job)).thenReturn(Optional.of(receipt));
        when(documents.find(document)).thenReturn(Optional.of(new KnowledgeDocumentManagementPort.DocumentState(document,5,false)));
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    private RequestPostProcessor admin() {
        return jwt().jwt(token->token.subject(subject)).authorities(new SimpleGrantedAuthority(KnowledgeAdminSecurityConfiguration.AUTHORITY));
    }
    private String body() { return """
            {"sourceKey":"public-guide","title":"公开指南","text":"公开说明","locale":"zh-CN",
             "minimumAppVersionCode":1,"maximumAppVersionCode":2,"idempotencyKey":"import-key-0000001"}
            """; }
    @Test void anonymousAndOrdinaryAccountCannotReachAnyManagementAction() throws Exception {
        for(var request:List.of(post("/admin/knowledge/imports").contentType(MediaType.APPLICATION_JSON).content(body()),
                get("/admin/knowledge/imports/{id}",job),get("/admin/knowledge/cleanup-status"),
                delete("/admin/knowledge/documents/{id}",document).param("expectedVersion","5"))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
            mvc.perform(request.with(jwt().jwt(token->token.subject(subject)))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(registration,documents,cleanupStatus);
    }
    @Test void importsAreAcceptedAndPollReturnsCurrentDocumentRevisionNotContentVersion() throws Exception {
        for(int n=0;n<2;n++) mvc.perform(post("/admin/knowledge/imports").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.id").value(job.toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING")).andExpect(jsonPath("$.data.documentVersion").value(2))
                .andExpect(jsonPath("$.data.documentRevision").value(5)).andExpect(jsonPath("$.data.text").doesNotExist())
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist());
        mvc.perform(get("/admin/knowledge/imports/{id}",job).with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentRevision").value(5));
        verify(registration,times(2)).register(argThat(r->r.sourceKey().equals("public-guide") && r.idempotencyKey().equals("import-key-0000001")),any());
    }
    @Test void deleteDelegatesOnlyExpectedDocumentRevision() throws Exception {
        mvc.perform(delete("/admin/knowledge/documents/{id}",document).param("expectedVersion","5").with(admin()))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(documents).invalidate(document,5);verifyNoInteractions(registration);
    }
    @ParameterizedTest @ValueSource(strings={"../guide","https://example.org/doc","C:\\guide","/etc/passwd"})
    void pathsAndUrlsAreNotSourceKeys(String source) throws Exception {
        String escaped=new ObjectMapper().writeValueAsString(source);
        mvc.perform(post("/admin/knowledge/imports").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(body().replace("\"public-guide\"",escaped))).andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(registration,documents);
    }
    @ParameterizedTest @ValueSource(strings={"\"url\":\"https://example.org\",","\"profileId\":\"other-model\",","\"owner\":\"other\",","\"sourceKey\":\"duplicate\","})
    void extraAndDuplicateFieldsCannotOverrideServerConfiguration(String inserted) throws Exception {
        mvc.perform(post("/admin/knowledge/imports").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(body().replaceFirst("\\{","{"+inserted))).andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(registration,documents);
    }
    @Test void utf8ByteLimitAndTotalBodyLimitAreCheckedBeforeRegistration() throws Exception {
        mvc.perform(post("/admin/knowledge/imports").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(body().replace("公开说明","中".repeat(87382)))).andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/admin/knowledge/imports").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(" ".repeat(2*1024*1024+1))).andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(registration,documents);
    }
    @Test void malformedEncodingAndNumericCoercionAreRejected() throws Exception {
        mvc.perform(post("/admin/knowledge/imports").with(admin()).contentType(MediaType.APPLICATION_JSON).content(new byte[]{(byte)0xc3,0x28}))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/admin/knowledge/imports").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(body().replace("\"minimumAppVersionCode\":1","\"minimumAppVersionCode\":\"1\"")))
                .andExpect(status().isUnprocessableEntity());verifyNoInteractions(registration,documents);
    }
    @Test void currentAccountAndDeviceAreRecheckedDespiteScope() throws Exception {
        when(accounts.isActive(any())).thenReturn(false);
        mvc.perform(get("/admin/knowledge/imports/{id}",job).with(admin())).andExpect(status().isConflict());
        when(accounts.isActive(any())).thenReturn(true);when(devices.isAllowedJwtDevice(any())).thenReturn(false);
        mvc.perform(delete("/admin/knowledge/documents/{id}",document).param("expectedVersion","5").with(admin())).andExpect(status().isForbidden());
        verifyNoInteractions(registration,documents);
    }
    @Test void deletedSourceAndVersionConflictCannotBeReportedAsSuccess() throws Exception {
        when(documents.find(document)).thenReturn(Optional.of(new KnowledgeDocumentManagementPort.DocumentState(document,6,true)));
        mvc.perform(get("/admin/knowledge/imports/{id}",job).with(admin())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.reasonCode").value("SOURCE_DELETED"));
        doThrow(new KnowledgeImportException(KnowledgeImportException.Kind.VERSION_MISMATCH)).when(documents).invalidate(document,5);
        mvc.perform(delete("/admin/knowledge/documents/{id}",document).param("expectedVersion","5").with(admin())).andExpect(status().isConflict());
    }
    @Test void storageFailureDoesNotLeakMessageOrRetryRegistration() throws Exception {
        when(registration.register(any(),any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("secret-sql-text"));
        mvc.perform(post("/admin/knowledge/imports").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isServiceUnavailable()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-sql-text"))));
        verify(registration).register(any(),any());
    }

    @Test void cleanupStatusIsNoStoreFiniteProjectionAndCannotAcknowledgeOrStartWork() throws Exception {
        var now=java.time.Instant.parse("2026-09-12T12:00:00Z");
        when(cleanupStatus.read()).thenReturn(new KnowledgeCleanupStatusPort.Snapshot(now,now.minusSeconds(901),now,4,
                KnowledgeCleanupStatusPort.LeaseState.EXPIRED,10000,2,3,true,1,2));
        mvc.perform(get("/admin/knowledge/cleanup-status").with(admin())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.state").value("CLEANUP_PENDING"))
                .andExpect(jsonPath("$.data.countsTruncated").value(true))
                .andExpect(jsonPath("$.data.overdue").value(true))
                .andExpect(jsonPath("$.data.pendingOverdueAlerts").value(2))
                .andExpect(jsonPath("$.data.localScanEnabled").value(true))
                .andExpect(jsonPath("$.data.leaseToken").doesNotExist())
                .andExpect(jsonPath("$.data.documentId").doesNotExist())
                .andExpect(jsonPath("$.data.text").doesNotExist());
        verify(cleanupStatus).read(); verifyNoInteractions(registration,documents);
    }

    @Test void cleanupRechecksActiveAccountAndDeviceBeforeStorage() throws Exception {
        when(accounts.isActive(any())).thenReturn(false);
        mvc.perform(get("/admin/knowledge/cleanup-status").with(admin())).andExpect(status().isConflict());
        when(accounts.isActive(any())).thenReturn(true);when(devices.isAllowedJwtDevice(any())).thenReturn(false);
        mvc.perform(get("/admin/knowledge/cleanup-status").with(admin())).andExpect(status().isForbidden());
        verifyNoInteractions(cleanupStatus);
    }

    @Test void cleanupStorageFailureIs503NotEmptyBacklogAndDoesNotEchoSqlOrRetry() throws Exception {
        when(cleanupStatus.read()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("PRIVATE_SQL_SENTINEL"));
        mvc.perform(get("/admin/knowledge/cleanup-status").with(admin())).andExpect(status().isServiceUnavailable())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE_SQL_SENTINEL"))))
                .andExpect(jsonPath("$.data.state").doesNotExist());
        verify(cleanupStatus).read();
    }
    private String signed(String issuer, String audience, String scope, java.time.Instant expires) {
        var claims=org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                .issuer(issuer).audience(List.of(audience)).subject(subject)
                .issuedAt(java.time.Instant.now().minusSeconds(600)).expiresAt(expires)
                .claim("scope",scope).claim("device_public_key_sha256","ab".repeat(32)).build();
        return encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(
                        org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }
    private String signedAdmin() {
        return signed("test-knowledge-issuer","test-knowledge-audience","knowledge:manage",java.time.Instant.now().plusSeconds(300));
    }

    @Test void realSignedBearerMapsExactScopeAndPassesDeviceDigest() throws Exception {
        mvc.perform(get("/admin/knowledge/imports/{id}",job).header("Authorization","Bearer "+signedAdmin()))
                .andExpect(status().isOk());
        verify(registration).find(job); verify(devices).isAllowedJwtDevice("ab".repeat(32));
    }

    @Test void realBearerInvalidIssuerAudienceExpirationAndSignatureNeverReachStorage() throws Exception {
        var future=java.time.Instant.now().plusSeconds(300);
        var valid=signedAdmin();
        var pieces=valid.split("\\.");
        var signature=Base64.getUrlDecoder().decode(pieces[2]); signature[0]^=1;
        var tampered=pieces[0]+"."+pieces[1]+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        for(String token:List.of("malformed",tampered,
                signed("wrong","test-knowledge-audience","knowledge:manage",future),
                signed("test-knowledge-issuer","wrong","knowledge:manage",future),
                signed("test-knowledge-issuer","test-knowledge-audience","knowledge:manage",java.time.Instant.now().minusSeconds(300)))) {
            mvc.perform(get("/admin/knowledge/imports/{id}",job).header("Authorization","Bearer "+token))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        }
        verifyNoInteractions(registration,documents,cleanupStatus,accounts,devices);
    }

    @Test void realSignedSimilarScopeCannotObtainManagementPermission() throws Exception {
        for(String scope:List.of("", "knowledge:read", "knowledge:manage-extra", "SCOPE_knowledge:manage")) {
            var token=signed("test-knowledge-issuer","test-knowledge-audience",scope,java.time.Instant.now().plusSeconds(300));
            mvc.perform(get("/admin/knowledge/imports/{id}",job).header("Authorization","Bearer "+token))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(registration,documents,cleanupStatus);
    }

    @Test void ordinaryProductionIssuerDoesNotGrantKnowledgeManagement() throws Exception {
        var issuer=new com.aifriend.identity.infrastructure.JwtAccessTokenAdapter(encoder,identityProperties);
        var now=java.time.Instant.now();
        var user=new com.aifriend.identity.domain.UserAccount(UUID.randomUUID(),
                com.aifriend.identity.domain.UserStatus.ACTIVE,1,now);
        var token=issuer.issue(user,now,HexFormat.of().parseHex("ab".repeat(32)));
        mvc.perform(get("/admin/knowledge/imports/{id}",job).header("Authorization","Bearer "+token.value()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(registration,documents,cleanupStatus);
    }

    @Test void validRealBearerStillRechecksAccountAndDevice() throws Exception {
        String token=signedAdmin(); when(accounts.isActive(any())).thenReturn(false);
        mvc.perform(get("/admin/knowledge/imports/{id}",job).header("Authorization","Bearer "+token))
                .andExpect(status().isConflict());
        when(accounts.isActive(any())).thenReturn(true); when(devices.isAllowedJwtDevice(any())).thenReturn(false);
        mvc.perform(get("/admin/knowledge/imports/{id}",job).header("Authorization","Bearer "+token))
                .andExpect(status().isForbidden());
        verifyNoInteractions(registration,documents,cleanupStatus);
    }

    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({KnowledgeAdminSecurityConfiguration.class,KnowledgeAdminController.class,KnowledgeAdminExceptionHandler.class,GlobalExceptionHandler.class})
    static class Config {
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean SecurityKeyMaterial keys() {
            byte[] bytes=new byte[32]; new java.security.SecureRandom().nextBytes(bytes);
            var key=new javax.crypto.spec.SecretKeySpec(bytes,"HmacSHA256"); Arrays.fill(bytes,(byte)0);
            return new SecurityKeyMaterial(key,null,null);
        }
        @Bean IdentitySecurityProperties identityProperties() {
            return new IdentitySecurityProperties("test-knowledge-issuer","test-knowledge-audience",
                    java.time.Duration.ofMinutes(5),java.time.Duration.ofDays(1),"","","");
        }
        @Bean JwtDecoder decoder(SecurityKeyMaterial keys,IdentitySecurityProperties properties) {
            return new JwtConfiguration().jwtDecoder(keys,properties);
        }
        @Bean org.springframework.security.oauth2.jwt.JwtEncoder encoder(SecurityKeyMaterial keys) {
            return new JwtConfiguration().jwtEncoder(keys);
        }
        @Bean ActiveAccountStatusPort accounts() { return mock(ActiveAccountStatusPort.class); }
        @Bean DeviceTrustService devices() { return mock(DeviceTrustService.class); }
        @Bean KnowledgeImportRegistrationPort registration() { return mock(KnowledgeImportRegistrationPort.class); }
        @Bean KnowledgeDocumentManagementPort documents() { return mock(KnowledgeDocumentManagementPort.class); }
        @Bean KnowledgeCleanupStatusPort cleanupStatus() { return mock(KnowledgeCleanupStatusPort.class); }
        @Bean BuildSpecification specification() { return new BuildSpecification(Optional.empty(),"v1","v1"); }
    }
}
