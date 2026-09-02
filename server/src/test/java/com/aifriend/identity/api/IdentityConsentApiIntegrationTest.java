package com.aifriend.identity.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 微信本地适配器、JWT、Flyway、授权历史和刷新令牌重放保护的集成测试。
 *
 * @author Codex
 * @since 1.0.0
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentityConsentApiIntegrationTest {

    private static final String TEST_KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("ai_friend")
            .withUsername("ai_friend")
            .withPassword("test-only-password");

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("ai-friend.security.jwt-signing-key-base64", () -> TEST_KEY);
        registry.add("ai-friend.security.data-encryption-key-base64", () -> TEST_KEY);
        registry.add("ai-friend.security.subject-hmac-key-base64", () -> TEST_KEY);
    }

    @Test
    void shouldCompleteIdentityConsentAndRefreshReplayFlow() {
        JsonNode login = post("/api/v1/auth/wechat/sessions", """
                {
                  "code": "local_owner.%s",
                  "device": {
                    "platform": "ANDROID",
                    "osVersion": "12",
                    "appVersion": "0.1.0-test",
                    "deviceModel": "integration-test"
                  }
                }
                """.formatted(UUID.randomUUID()), null, HttpStatus.OK);

        String accessToken = login.at("/data/accessToken").asText();
        String refreshToken = login.at("/data/refreshToken").asText();
        assertThat(login.at("/data/user/status").asText()).isEqualTo("ACTIVE");

        HttpHeaders consentHeaders = bearerHeaders(accessToken);
        consentHeaders.set("Idempotency-Key", "integration-consent-0001");
        ResponseEntity<JsonNode> granted = restTemplate.exchange(
                "/api/v1/privacy/consents/BASIC_IDENTITY",
                HttpMethod.PUT,
                new HttpEntity<>("""
                        {
                          "decision": "GRANTED",
                          "policyVersion": "privacy-v1",
                          "confirmedAt": "%s"
                        }
                        """.formatted(Instant.now()), consentHeaders),
                JsonNode.class);
        assertThat(granted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(granted.getBody().at("/data/decision").asText()).isEqualTo("GRANTED");

        ResponseEntity<JsonNode> consents = restTemplate.exchange(
                "/api/v1/privacy/consents",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(accessToken)),
                JsonNode.class);
        assertThat(consents.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(consents.getBody().at("/data/0/type").asText()).isEqualTo("BASIC_IDENTITY");

        JsonNode rotated = post("/api/v1/auth/tokens/refresh", """
                {"refreshToken": "%s"}
                """.formatted(refreshToken), null, HttpStatus.OK);
        String rotatedRefreshToken = rotated.at("/data/refreshToken").asText();
        assertThat(rotatedRefreshToken).isNotEqualTo(refreshToken);

        post("/api/v1/auth/tokens/refresh", """
                {"refreshToken": "%s"}
                """.formatted(refreshToken), null, HttpStatus.UNAUTHORIZED);
        JsonNode revokedFamily = post("/api/v1/auth/tokens/refresh", """
                {"refreshToken": "%s"}
                """.formatted(rotatedRefreshToken), null, HttpStatus.UNAUTHORIZED);
        assertThat(revokedFamily.path("code").asText()).isEqualTo("AUTH_REQUIRED");
    }

    private JsonNode post(String path, String body, HttpHeaders headers, HttpStatus expectedStatus) {
        HttpHeaders requestHeaders = headers == null ? new HttpHeaders() : headers;
        requestHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                path,
                new HttpEntity<>(body, requestHeaders),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private HttpHeaders bearerHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
