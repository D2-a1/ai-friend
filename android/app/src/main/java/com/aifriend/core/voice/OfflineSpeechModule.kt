package com.aifriend.core.voice

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 本机离线中文语音依赖绑定。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class OfflineSpeechModule {
    @Binds
    abstract fun bindOfflineSpeechPort(
        implementation: AndroidOfflineSpeechAdapter,
    ): OfflineSpeechPort
}
