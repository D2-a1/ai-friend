package com.aifriend.core.security

import kotlinx.serialization.Serializable

/**
 * Keystore 密文中保存的最小登录会话。
 *
 * 时间统一保存为 ISO-8601 UTC 字符串，避免平台序列化差异。
 *
 * @author codex
 * @since 2026-08-04
 */
@Serializable
data class StoredAuthSession(
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val refreshToken: String,
    val refreshTokenExpiresAt: String,
    val userId: String,
    val userStatus: String,
    val displayName: String? = null,
)
