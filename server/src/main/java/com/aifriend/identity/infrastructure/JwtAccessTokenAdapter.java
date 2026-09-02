package com.aifriend.identity.infrastructure;

import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import com.aifriend.identity.application.AccessToken;
import com.aifriend.identity.application.AccessTokenPort;
import com.aifriend.identity.domain.UserAccount;
import com.aifriend.shared.security.IdentitySecurityProperties;
import com.aifriend.shared.security.PublicIdCodec;

/**
 * 使用 HS256 签发短期访问令牌的适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JwtAccessTokenAdapter implements AccessTokenPort {

    private final JwtEncoder jwtEncoder;
    private final IdentitySecurityProperties properties;

    /**
     * 创建 JWT 访问令牌适配器。
     *
     * @param jwtEncoder JWT 编码器
     * @param properties 身份安全配置
     */
    public JwtAccessTokenAdapter(JwtEncoder jwtEncoder, IdentitySecurityProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    /**
     * 为 ACTIVE 用户签发含 iss/aud/exp/jti 的 JWT。
     *
     * @param user 用户账号
     * @param issuedAt 签发时间
     * @param devicePublicKeySha256 已验证设备公钥摘要；门禁关闭时为空
     * @return 访问令牌
     */
    @Override
    public AccessToken issue(UserAccount user, Instant issuedAt, byte[] devicePublicKeySha256) {
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(PublicIdCodec.userId(user.id()))
                .id(UUID.randomUUID().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("account_generation", user.accountGeneration());
        if (devicePublicKeySha256 != null) {
            claimsBuilder.claim(
                    "device_public_key_sha256",
                    HexFormat.of().formatHex(devicePublicKeySha256));
        }
        JwtClaimsSet claims = claimsBuilder.build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(token, expiresAt);
    }
}
