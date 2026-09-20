package com.aifriend.feature.wechat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatShareVisualEvidenceRuleTest {
    private val expected = "Demo_123".toCharArray()
    private val point = WechatCalibrationPixelPoint(80, 450)
    private fun line(text: String, top: Int, left: Int = 100, right: Int = 600) =
        WechatVisualTextLine(text, left, top, right, top + 40)
    private fun search() = listOf(line("Demo_123", 180), line("取消", 180, 1050, 1150),
        line("联系人", 300), line("亲友", 390), line("微信号：Demo_123", 460))

    @Test fun exactSearchAndResultAllowOnlyCorrespondingRow() {
        assertTrue(WechatShareVisualEvidenceRule.searchResult(search(), expected, point))
        assertFalse(WechatShareVisualEvidenceRule.searchResult(search(), expected, point.copy(y = 900)))
        assertFalse(WechatShareVisualEvidenceRule.searchResult(search(), expected, point.copy(y = 200)))
    }
    @Test fun missingSearchAndPinnedPageFail() {
        assertFalse(WechatShareVisualEvidenceRule.searchResult(search().drop(1), expected, point))
        assertFalse(WechatShareVisualEvidenceRule.searchResult(listOf(line("最近转发", 300)), expected, point))
        assertFalse(WechatShareVisualEvidenceRule.searchResult(search().filterNot { it.text == "联系人" }, expected, point))
    }
    @Test fun wrongCasePunctuationAndTwoResultsFail() {
        for (wrong in listOf("Demo123", "demo_123", "Other_01")) {
            assertFalse(WechatShareVisualEvidenceRule.searchResult(
                search().dropLast(1) + line("微信号：$wrong", 460), expected, point))
        }
        assertFalse(WechatShareVisualEvidenceRule.searchResult(search() + line("微信号：Other_01", 800), expected, point))
    }
    @Test fun queryStillRequiresEveryAccountCharacterAndRejectsExtraInput() {
        for (wrong in listOf("Q Demo_123", "QDemo_123", "demo_123", "Demo123", "Demo_123 extra")) {
            assertFalse(WechatShareVisualEvidenceRule.searchResult(
                listOf(line(wrong, 180)) + search().drop(1), expected, point))
        }
    }
    @Test fun sendRequiresTwoUniqueButtonsAndMatchingLocation() {
        val buttons = listOf(line("取消", 1400, 300, 400), line("发送", 1400, 800, 900))
        assertTrue(WechatShareVisualEvidenceRule.sendButton(buttons, WechatCalibrationPixelPoint(850, 1420)))
        assertTrue(WechatShareVisualEvidenceRule.sendButton(
            buttons + line("取消", 180, 1050, 1150), WechatCalibrationPixelPoint(850, 1420)))
        assertFalse(WechatShareVisualEvidenceRule.sendButton(
            buttons + buttons.first(), WechatCalibrationPixelPoint(850, 1420)))
        assertFalse(WechatShareVisualEvidenceRule.sendButton(buttons, WechatCalibrationPixelPoint(350, 1420)))
        assertFalse(WechatShareVisualEvidenceRule.sendButton(buttons + buttons.last(), WechatCalibrationPixelPoint(850, 1420)))
        assertFalse(WechatShareVisualEvidenceRule.sendButton(buttons.drop(1), WechatCalibrationPixelPoint(850, 1420)))
    }
}
