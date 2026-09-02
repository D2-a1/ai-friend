package com.aifriend.core.security

/**
 * Keystore 保护的本地安全存储端口。
 *
 * @author codex
 * @since 2026-07-25
 */
interface SecureStorePort {
    suspend fun put(key: String, value: ByteArray)

    suspend fun get(key: String): ByteArray?

    suspend fun remove(key: String)

    suspend fun wipe()
}
