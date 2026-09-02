package com.aifriend.feature.consent

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 分项授权仓库依赖绑定。
 *
 * @author codex
 * @since 2026-08-04
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ConsentModule {

    @Binds
    abstract fun bindConsentRepository(
        implementation: DefaultConsentRepository,
    ): ConsentRepository
}
