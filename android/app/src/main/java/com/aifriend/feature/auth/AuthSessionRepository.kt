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

    /** 登录、恢复或退出边界的本机代次；普通token刷新不变。生产实现必须维护，旧测试替身默认0。 */
    val loginEpoch: Long get() = 0L

    suspend fun restore(): AuthSession?

    suspend fun loginWithWechatCode(code: String, device: WechatLoginDevice): AuthSession

    suspend fun refresh(): AuthSession

    suspend fun clearLocalSession()
}
