package com.aifriend.feature.task

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 任务语音链依赖绑定。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TaskModule {
    @Binds
    abstract fun bindTaskRepository(implementation: DefaultTaskRepository): TaskRepository

    @Binds
    abstract fun bindLocalTaskRecognizer(
        implementation: VoskLocalTaskRecognizer,
    ): LocalTaskRecognizer
}
