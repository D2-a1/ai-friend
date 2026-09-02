package com.aifriend.feature.guardian

import com.aifriend.core.voice.OfflineSpeechPort
import javax.inject.Inject
import javax.inject.Singleton

/** 只允许使用本机离线 voice 的固定唤醒应答端口。 */
interface GuardianAcknowledgement {
    suspend fun prepare(): Boolean
    suspend fun speak(): Boolean
    fun close()
}

/**
 * 使用共享本机离线中文语音端口播放固定应答；失败时由守护服务关闭本次启用。
 */
@Singleton
class OfflineGuardianAcknowledgement @Inject constructor(
    private val offlineSpeechPort: OfflineSpeechPort,
) : GuardianAcknowledgement {
    override suspend fun prepare(): Boolean = offlineSpeechPort.prepare()

    override suspend fun speak(): Boolean = offlineSpeechPort.speak(ACKNOWLEDGEMENT_TEXT)

    override fun close() = offlineSpeechPort.close()

    private companion object {
        const val ACKNOWLEDGEMENT_TEXT = "主人，我在"
    }
}
