package com.aifriend.feature.wechat

import com.aifriend.service.WechatAccessibilityService
import java.io.FileDescriptor
import java.io.PrintWriter
import java.io.StringWriter
import org.junit.Assert.*
import org.junit.Test

/** 只调用生产 dump 方法；不绑定辅助服务、不读取正式数据、不发送或点击。 */
class WechatMessageDiagnosticsDumpTest {
    @Test fun dumpReadsExistingPointAndNeverWritesOrExecutes() {
        val key = WechatCalibrationProfileKey("test", "Test", 35, 1260, 2750, 560,
            1000, WechatCalibrationOrientation.PORTRAIT, "8.0.76")
        val profile = WechatCalibrationProfile(key = key, points =
            WechatCalibrationPurpose.MESSAGE.targets.associateWith {
                checkNotNull(WechatNormalizedCalibrationPoint.fromPixels(800, 2400, 1260, 2750))
            }, updatedAtEpochMillis = 0)
        val service = WechatAccessibilityService()
        service.wechatRuntimeVersionProvider = WechatRuntimeVersionProvider { "8.0.76" }
        service.calibrationFingerprintProvider = WechatCalibrationFingerprintProvider { key }
        service.calibrationProfileRegistry = object : WechatCalibrationProfileRegistry {
            override fun list() = listOf(profile)
            override fun findExact(key: WechatCalibrationProfileKey): WechatCalibrationProfile? =
                profile.takeIf { it.key == key }
            override fun upsert(profile: WechatCalibrationProfile): Boolean = error("dump must not write")
            override fun remove(key: WechatCalibrationProfileKey): Boolean = error("dump must not delete")
        }
        WechatMessageDiagnostics.begin(1024)
        WechatMessageDiagnostics.stage(WechatMessageSelectionStage.SEND_BUTTON_NOT_VERIFIED)
        val output = StringWriter()
        val method = WechatAccessibilityService::class.java.getDeclaredMethod("dump",
            FileDescriptor::class.java, PrintWriter::class.java, Array<String>::class.java)
        method.isAccessible = true
        method.invoke(service, FileDescriptor(), PrintWriter(output), arrayOf("--wechat-message-diagnostics"))
        assertTrue(output.toString().contains("AI_FRIEND_MESSAGE_DIAGNOSTICS_V1"))
        assertTrue(output.toString().contains("savedPoint=SHARE_SEND_CONFIRM x=800 y=2400"))
        assertTrue(output.toString().contains("stage=SEND_BUTTON_NOT_VERIFIED"))
        assertFalse(output.toString().contains("targetSearchLocator"))
        assertFalse(output.toString().contains("transaction="))
    }
}
