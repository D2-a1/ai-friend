package com.aifriend.feature.voice.safety

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 安全指令注册依赖绑定。
 *
 * @author codex
 * @since 2026-08-13
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SafetyCommandModule {

    @Binds
    abstract fun bindSafetyCommandEnrollmentRepository(
        implementation: DefaultSafetyCommandEnrollmentRepository,
    ): SafetyCommandEnrollmentRepository
}
