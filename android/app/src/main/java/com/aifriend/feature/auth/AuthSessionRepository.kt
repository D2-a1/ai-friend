package com.aifriend.feature.auth

import kotlinx.coroutines.flow.StateFlow

/**
 * 登录会话、刷新轮换和本地退出端口。
 *
 * @author codex
 * @since 2026-08-04
 */
interface AuthSessionRepository {
    val session: StateFlow<AuthSession?>

    suspend fun restore(): AuthSession?

    suspend fun loginWithWechatCode(code: String, device: WechatLoginDevice): AuthSession

    suspend fun refresh(): AuthSession

    suspend fun clearLocalSession()
}
