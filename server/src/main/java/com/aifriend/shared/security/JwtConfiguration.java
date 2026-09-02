package com.aifriend.shared.security;

import java.util.List;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * JWT 编码、验签、签发方和接收方校验配置。
 *
 * @author Codex
 * @since 1.0.0
 */
@Configuration
public class JwtConfiguration {

    /**
     * 创建 JWT 配置。
     */
    public JwtConfiguration() {
    }

    /**
     * 创建 JWT 编码器。
     *
     * @param keyMaterial 安全密钥
     * @return JWT 编码器
     */
    @Bean
    public JwtEncoder jwtEncoder(SecurityKeyMaterial keyMaterial) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(keyMaterial.jwtSigningKey()));
    }

    /**
     * 创建 JWT 解码器并校验 issuer 与 audience。
     *
     * @param keyMaterial 安全密钥
     * @param properties 身份安全配置
     * @return JWT 解码器
     */
    @Bean
    public JwtDecoder jwtDecoder(
            SecurityKeyMaterial keyMaterial,
            IdentitySecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(keyMaterial.jwtSigningKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(properties.issuer());
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience().contains(properties.audience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "JWT audience 不匹配", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(issuerValidator, audienceValidator)));
        return decoder;
    }
}
