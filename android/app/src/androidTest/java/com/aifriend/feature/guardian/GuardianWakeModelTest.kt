package com.aifriend.feature.guardian

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 设备侧复验随 APK 模型摘要、受限解压和 Vosk native 初始化。 */
@RunWith(AndroidJUnit4::class)
class GuardianWakeModelTest {

    @Test
    fun bundledChineseModelInstallsAndInitializesOfflineRecognizer() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installer = VoskModelAssetInstaller(context)
        val detector = VoskGuardianWakeWordDetector(
            context,
            installer,
            object : PersonalWakeWordVerifier {
                override suspend fun prepare(): Boolean = true
                override fun matches(samples: ShortArray, repetitions: Int): Boolean = true
                override fun close() = Unit
            },
        )
        try {
            assertEquals(GuardianWakeReadiness.READY, detector.prepare())
            val modelDirectory = installer.install()
            assertTrue(modelDirectory.resolve("am/final.mdl").isFile)
            assertTrue(modelDirectory.resolve("graph/HCLr.fst").isFile)
        } finally {
            detector.close()
        }
    }
}
