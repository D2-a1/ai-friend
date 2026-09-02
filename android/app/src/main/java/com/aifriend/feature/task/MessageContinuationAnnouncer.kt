package com.aifriend.feature.task

import com.aifriend.core.voice.OfflineSpeechPort
import javax.inject.Inject
import javax.inject.Singleton

/** 使用设备已安装离线中文 voice 播放继续窗口固定提示。 */
@Singleton
class MessageContinuationAnnouncer @Inject constructor(
    private val offlineSpeechPort: OfflineSpeechPort,
) {
    suspend fun announceAvailable(contactLabel: String): Boolean {
        if (contactLabel.isBlank()) return false
        return offlineSpeechPort.speak("可以继续给${contactLabel.take(MAXIMUM_LABEL_LENGTH)}说话")
    }

    suspend fun announceSleeping(): Boolean = offlineSpeechPort.speak("主人，我休息了")

    fun close() = offlineSpeechPort.close()

    private companion object {
        const val MAXIMUM_LABEL_LENGTH = 80
    }
}
