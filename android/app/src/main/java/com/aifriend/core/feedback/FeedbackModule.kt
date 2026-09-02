package com.aifriend.core.feedback

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 适老化本机反馈端口绑定。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FeedbackModule {
    @Binds
    abstract fun bindHapticFeedbackPort(
        implementation: AndroidHapticFeedbackAdapter,
    ): HapticFeedbackPort
}
