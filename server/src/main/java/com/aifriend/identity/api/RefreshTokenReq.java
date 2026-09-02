package com.aifriend.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 刷新令牌轮换请求。
 *
 * @param refreshToken 刷新令牌明文
 * @param publicKeySpkiBase64 Android Keystore X.509 SPKI 公钥 Base64
 * @param proofBase64 绑定当前轮换 refresh token 的 SHA256withECDSA 签名
 * @author Codex
 * @since 1.0.0
 */
public record RefreshTokenReq(
        @NotBlank @Size(min = 32, max = 4096) String refreshToken,
        @Size(max = 512) String publicKeySpkiBase64,
        @Size(max = 256) String proofBase64) {
}
