package com.aifriend.feature.contact

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 联系人仓库依赖绑定。
 *
 * @author codex
 * @since 2026-08-07
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ContactModule {

    @Binds
    abstract fun bindContactRepository(
        implementation: DefaultContactRepository,
    ): ContactRepository
}
