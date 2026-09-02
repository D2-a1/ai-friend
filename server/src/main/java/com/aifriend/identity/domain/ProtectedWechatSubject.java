package com.aifriend.identity.domain;

/**
 * 加密微信主体及其 HMAC 查询键。
 *
 * @param cipher AES-GCM 密文
 * @param hash HMAC-SHA-256 查询键
 * @author Codex
 * @since 1.0.0
 */
public record ProtectedWechatSubject(byte[] cipher, byte[] hash) {
}
