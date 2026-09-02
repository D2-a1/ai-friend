package com.aifriend.shared.security;

import javax.crypto.SecretKey;

/**
 * 运行时身份安全密钥集合。
 *
 * @param jwtSigningKey JWT HMAC 密钥
 * @param dataEncryptionKey 敏感数据 AES 密钥
 * @param subjectHmacKey 微信主体查询 HMAC 密钥
 * @author Codex
 * @since 1.0.0
 */
public record SecurityKeyMaterial(
        SecretKey jwtSigningKey,
        SecretKey dataEncryptionKey,
        SecretKey subjectHmacKey) {
}
