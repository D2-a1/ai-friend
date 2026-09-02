package com.aifriend.feature.auth

import java.time.Instant

/**
 * Android 业务层可见的最小登录会话，不向 UI 暴露 token。
 *
 * @author codex
 * @since 2026-08-04
 */
data class AuthSession(
    val userId: String,
    val userStatus: String,
    val displayName: String?,
    val accessTokenExpiresAt: Instant,
    val refreshTokenExpiresAt: Instant,
)

/**
 * 登录时提交的非敏感 Android 设备上下文。
 */
data class WechatLoginDevice(
    val osVersion: String,
    val appVersion: String,
    val deviceModel: String?,
)
