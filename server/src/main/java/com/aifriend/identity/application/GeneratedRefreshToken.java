package com.aifriend.identity.application;

import com.aifriend.identity.domain.StoredRefreshToken;

/**
 * 新生成刷新令牌的明文与无明文持久化快照。
 *
 * @param value 仅返回客户端的刷新令牌明文
 * @param storedToken 只含摘要的持久化快照
 * @author Codex
 * @since 1.0.0
 */
public record GeneratedRefreshToken(String value, StoredRefreshToken storedToken) {
}
