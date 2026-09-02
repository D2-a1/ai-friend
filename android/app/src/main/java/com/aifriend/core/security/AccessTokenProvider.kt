package com.aifriend.core.security

/**
 * OkHttp 同步读取当前内存访问令牌的最小端口。
 *
 * @author codex
 * @since 2026-08-04
 */
fun interface AccessTokenProvider {
    fun currentAccessToken(): String?
}
