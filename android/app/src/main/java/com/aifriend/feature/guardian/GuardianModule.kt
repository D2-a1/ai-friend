package com.aifriend.feature.guardian

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 小友守护的本地录音与唤醒依赖绑定。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class GuardianModule {
    @Binds
    abstract fun bindGuardianAudioStream(
        implementation: AndroidGuardianAudioStream,
    ): GuardianAudioStream

    @Binds
    abstract fun bindGuardianWakeWordDetector(
        implementation: VoskGuardianWakeWordDetector,
    ): GuardianWakeWordDetector

    @Binds
    abstract fun bindPersonalWakeWordVerifier(
        implementation: LocalPersonalWakeWordVerifier,
    ): PersonalWakeWordVerifier

    @Binds
    abstract fun bindGuardianAcknowledgement(
        implementation: OfflineGuardianAcknowledgement,
    ): GuardianAcknowledgement
}
