package com.aifriend.feature.audio

import android.os.Build
import com.aifriend.BuildConfig
import com.aifriend.core.audio.AliasRecordingQualityAnalyzer
import com.aifriend.core.audio.AndroidAudioCaptureAdapter
import com.aifriend.core.audio.AndroidAudioPlaybackAdapter
import com.aifriend.core.audio.AudioCapturePort
import com.aifriend.core.audio.AudioPlaybackPort
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 受限音频上传仓库依赖绑定。
 *
 * @author codex
 * @since 2026-08-10
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AudioModule {

    @Binds
    abstract fun bindAudioCapturePort(
        implementation: AndroidAudioCaptureAdapter,
    ): AudioCapturePort

    @Binds
    abstract fun bindAudioPlaybackPort(
        implementation: AndroidAudioPlaybackAdapter,
    ): AudioPlaybackPort

    @Binds
    abstract fun bindAudioUploadRepository(
        implementation: DefaultAudioUploadRepository,
    ): AudioUploadRepository
}

/** 录音质量分析器的运行环境选择。 */
@Module
@InstallIn(SingletonComponent::class)
object AudioQualityModule {

    /**
     * 只在 Debug 模拟器启用低电平兼容档，Release 和真机保持正式阈值。
     *
     * @return 当前运行环境对应的录音质量分析器
     */
    @Provides
    fun provideAliasRecordingQualityAnalyzer(): AliasRecordingQualityAnalyzer {
        val emulator = Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true)
        return if (BuildConfig.DEBUG && emulator) {
            AliasRecordingQualityAnalyzer.emulatorCompatible()
        } else {
            AliasRecordingQualityAnalyzer()
        }
    }
}
