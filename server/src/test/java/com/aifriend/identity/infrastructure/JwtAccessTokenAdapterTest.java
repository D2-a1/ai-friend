package com.aifriend.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import com.aifriend.identity.domain.UserAccount;
import com.aifriend.identity.domain.UserStatus;
import com.aifriend.shared.security.IdentitySecurityProperties;

class JwtAccessTokenAdapterTest {

    @Test
    void shouldPutVerifiedDeviceDigestIntoSignedAccessTokenClaims() {
        Instant issuedAt = Instant.parse("2026-08-29T00:00:00Z");
        byte[] deviceDigest = HexFormat.of().parseHex("ab".repeat(32));
        JwtEncoder encoder = mock(JwtEncoder.class);
        AtomicReference<Map<String, Object>> capturedClaims = new AtomicReference<>();
        when(encoder.encode(any(JwtEncoderParameters.class))).thenAnswer(invocation -> {
            JwtEncoderParameters parameters = invocation.getArgument(0);
            Map<String, Object> claims = parameters.getClaims().getClaims();
            capturedClaims.set(claims);
            return new Jwt(
                    "signed-token",
                    parameters.getClaims().getIssuedAt(),
                    parameters.getClaims().getExpiresAt(),
                    Map.of("alg", "HS256"),
                    claims);
        });
        JwtAccessTokenAdapter adapter = new JwtAccessTokenAdapter(
                encoder,
                new IdentitySecurityProperties(
                        "ai-friend",
                        "ai-friend-android",
                        Duration.ofMinutes(15),
                        Duration.ofDays(30),
                        "",
                        "",
                        ""));

        var token = adapter.issue(
                new UserAccount(UUID.randomUUID(), UserStatus.ACTIVE, 1, issuedAt),
                issuedAt,
                deviceDigest);

        assertThat(token.value()).isEqualTo("signed-token");
        assertThat(capturedClaims.get())
                .containsEntry("device_public_key_sha256", "ab".repeat(32));
    }
}
