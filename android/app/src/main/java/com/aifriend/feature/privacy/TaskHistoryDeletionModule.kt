package com.aifriend.feature.privacy

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 任务历史清除依赖绑定。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TaskHistoryDeletionModule {
    @Binds abstract fun bindRepository(implementation: DefaultTaskHistoryDeletionRepository): TaskHistoryDeletionRepository

    @Binds
    abstract fun bindAccountClosureRepository(
        implementation: DefaultAccountClosureRepository,
    ): AccountClosureRepository
}
