package com.aifriend.feature.personalization

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PersonalMemoryModule {
    @Binds
    @Singleton
    abstract fun bindPersonalMemoryRepository(
        implementation: DefaultPersonalMemoryRepository,
    ): PersonalMemoryRepository
}