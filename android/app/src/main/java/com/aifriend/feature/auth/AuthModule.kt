package com.aifriend.feature.auth

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 登录会话仓库依赖绑定。
 *
 * @author codex
 * @since 2026-08-04
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    abstract fun bindAuthSessionRepository(
        implementation: DefaultAuthSessionRepository,
    ): AuthSessionRepository

    @Binds
    abstract fun bindWechatLoginPort(
        implementation: AndroidWechatLoginAdapter,
    ): WechatLoginPort

    @Binds
    abstract fun bindDeviceIdentityPort(
        implementation: AndroidDeviceIdentity,
    ): DeviceIdentityPort
}
