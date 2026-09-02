package com.aifriend.feature.invitation

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 亲友邀请仓库依赖绑定。
 *
 * @author codex
 * @since 2026-08-07
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class InvitationModule {

    @Binds
    abstract fun bindInvitationRepository(
        implementation: DefaultInvitationRepository,
    ): InvitationRepository
}
