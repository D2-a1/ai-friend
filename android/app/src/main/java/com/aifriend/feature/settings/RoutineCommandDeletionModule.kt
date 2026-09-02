package com.aifriend.feature.settings

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 日常指令模板清除仓库依赖绑定。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RoutineCommandDeletionModule {
    @Binds
    abstract fun bindRoutineCommandDeletionRepository(
        implementation: DefaultRoutineCommandDeletionRepository,
    ): RoutineCommandDeletionRepository
}
