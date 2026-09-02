package com.aifriend.feature.guardian

import androidx.test.core.app.ApplicationProvider
import com.aifriend.core.settings.FontLevel
import com.aifriend.core.settings.SpeechRatePreference
import com.aifriend.core.settings.SpeechVolumePreference
import com.aifriend.core.settings.UserSettings
import com.aifriend.core.settings.UserSettingsRepository
import com.aifriend.core.voice.AndroidOfflineSpeechAdapter
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/** 需要目标真机、测试员和已安装离线中文 voice 的抢话设备入口。 */
class GuardianBargeInDeviceProbeTest {

    @Ignore("需在目标真机显式授权麦克风后运行，并保证测试期间无人说话")
    @Test
    fun sameOfflineAcknowledgementDoesNotBecomeUserSpeech() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val acknowledgement = OfflineGuardianAcknowledgement(
            AndroidOfflineSpeechAdapter(context, FixedDeviceProbeSettingsRepository),
        )
        val session = GuardianBargeInDeviceProbe(context).start(GuardianBargeInScenario.ECHO_ONLY)
        try {
            assertTrue(acknowledgement.prepare())
            session.acknowledgementStarted()
            assertTrue(acknowledgement.speak())
            session.acknowledgementFinished()
            delay(300)

            val report = session.finish()
            assertEquals(GuardianBargeInDecision.PASSED, report.diagnostic.decision)
            assertEquals(0, report.diagnostic.falseVoiceStarts)
        } catch (error: Throwable) {
            session.abort()
            throw error
        } finally {
            acknowledgement.close()
        }
    }

    @Ignore("需目标真机测试驱动在测试员实际开口时调用 markExternalSpeechStarted")
    @Test
    fun markedSpeechDuringAcknowledgementIsDetectedWithinFiveHundredMilliseconds() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val acknowledgement = OfflineGuardianAcknowledgement(
            AndroidOfflineSpeechAdapter(context, FixedDeviceProbeSettingsRepository),
        )
        val session = GuardianBargeInDeviceProbe(context).start(GuardianBargeInScenario.MARKED_SPEECH)
        try {
            assertTrue(acknowledgement.prepare())
            session.acknowledgementStarted()
            val speaking = async { acknowledgement.speak() }
            delay(250)
            session.markExternalSpeechStarted()
            assertTrue(speaking.await())
            session.acknowledgementFinished()
            delay(300)

            val report = session.finish()
            assertEquals(GuardianBargeInDecision.PASSED, report.diagnostic.decision)
            assertTrue(report.diagnostic.detectionLatencyMs in 0L..500L)
        } catch (error: Throwable) {
            session.abort()
            throw error
        } finally {
            acknowledgement.close()
        }
    }
}

/** 手工设备探针固定使用正常语速，不依赖登录态或持久化设置。 */
private object FixedDeviceProbeSettingsRepository : UserSettingsRepository {
    override val settings = flowOf(UserSettings())

    override suspend fun current(): UserSettings = UserSettings()

    override suspend fun updateSpeechRate(value: SpeechRatePreference) = Unit

    override suspend fun updateSpeechVolume(value: SpeechVolumePreference) = Unit

    override suspend fun updateFontLevel(value: FontLevel) = Unit

    override suspend fun updateHighContrast(enabled: Boolean) = Unit

    override suspend fun clearAll() = Unit
}
