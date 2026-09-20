package com.aifriend.feature.wechat

import org.junit.Assert.*
import org.junit.Test

class WechatMessageDiagnosticsTest {
    @Test fun boundedAndNewAttemptDropsPreviousEvidence() {
        WechatMessageDiagnostics.begin(123)
        repeat(100) { WechatMessageDiagnostics.record(WechatMessageDiagnostics.Event.SEARCH_CHECK) }
        assertEquals(64, WechatMessageDiagnostics.snapshot().size)
        WechatMessageDiagnostics.begin(456)
        val snapshot = WechatMessageDiagnostics.snapshot()
        assertEquals(1, snapshot.size)
        assertTrue(snapshot.single().contains("AUDIO_BYTES first=456"))
    }
    @Test fun recordsFixedStageAndActualPixelPointWithoutFreeText() {
        WechatMessageDiagnostics.begin(128)
        WechatMessageDiagnostics.stage(WechatMessageSelectionStage.SEND_BUTTON_NOT_VERIFIED)
        WechatMessageDiagnostics.point(WechatCalibrationTarget.SHARE_SEND_CONFIRM, WechatCalibrationPixelPoint(800, 2400))
        val snapshot = WechatMessageDiagnostics.snapshot()
        assertTrue(snapshot[1].endsWith("stage=SEND_BUTTON_NOT_VERIFIED"))
        assertTrue(snapshot[2].endsWith("point=SHARE_SEND_CONFIRM x=800 y=2400"))
        WechatMessageDiagnostics.record(WechatMessageDiagnostics.Event.SDK_CALLBACK, 0)
        assertEquals(3, snapshot.size)
    }
}
