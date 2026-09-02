package com.aifriend.core.security

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 本地安全存储依赖绑定。
 *
 * @author codex
 * @since 2026-08-04
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    @Binds
    abstract fun bindSecureStore(implementation: AndroidKeystoreSecureStore): SecureStorePort
}
