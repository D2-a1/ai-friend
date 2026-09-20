package com.aifriend.feature.wechat

import org.junit.Assert.*
import org.junit.Test

class WechatLocatorRefinementRuleTest {
    @Test fun observedLabelStrokeDoesNotRelaxLocatorOrOtherPrefixes() {
        assertEquals("微信号: Demo_123", WechatLocatorRefinementRule.normalizeLabel("|微信号: Demo_123"))
        assertEquals("||微信号: Demo_123", WechatLocatorRefinementRule.normalizeLabel("||微信号: Demo_123"))
        assertEquals("其他|微信号: Demo_123", WechatLocatorRefinementRule.normalizeLabel("其他|微信号: Demo_123"))
        val invalid = WechatVisualTextEvidenceRule.contactProfile(listOf(
            row.copy(text = WechatLocatorRefinementRule.normalizeLabel("|微信号: Demo|_123"))))
        try { assertTrue(invalid.locatorCandidates.isEmpty()) } finally { invalid.clear() }
    }
    @Test fun normalizeOnlyFixedChineseLabelNeverAccountCharacters() {
        assertEquals("微信号：Demo_123", WechatLocatorRefinementRule.normalizeLabel("微信 号：Demo_123"))
        assertEquals("微信号: Demo _123", WechatLocatorRefinementRule.normalizeLabel("微 信 号: Demo _123"))
        assertEquals("其他微信号:Demo_123", WechatLocatorRefinementRule.normalizeLabel("其他微信号:Demo_123"))
        assertEquals("微信名称:Demo_123", WechatLocatorRefinementRule.normalizeLabel("微信名称:Demo_123"))
    }
    private val row = WechatVisualTextLine("微信号：Demo123", 50, 100, 300, 140)
    private val action = WechatVisualTextLine("音视频通话", 50, 400, 300, 450)

    @Test fun sameFrameTwoScalesMustAgreeExactly() {
        val merged = WechatLocatorRefinementRule.merge(
            listOf(row, action), row, "Demo_123".toCharArray(), "Demo_123".toCharArray(),
        )
        val parsed = WechatVisualTextEvidenceRule.contactProfile(checkNotNull(merged))
        try {
            assertEquals("Demo_123", parsed.locatorCandidates.single().concatToString())
            assertEquals(1, parsed.callEntryMatchCount)
        } finally { parsed.clear() }
    }

    @Test fun differingPunctuationCannotBeGuessedOrFilledIn() {
        assertNull(WechatLocatorRefinementRule.merge(
            listOf(row), row, "Demo_123".toCharArray(), "Demo123".toCharArray(),
        ))
    }

    @Test fun caseDifferenceOrMissingEvidenceMustFailClosed() {
        assertNull(WechatLocatorRefinementRule.merge(
            listOf(row), row, "Demo_123".toCharArray(), "demo_123".toCharArray(),
        ))
        assertNull(WechatLocatorRefinementRule.merge(listOf(row), row, null, "Demo_123".toCharArray()))
    }

    @Test fun unrelatedLocatorRowRemainsAmbiguous() {
        val other = row.copy(top = 200, bottom = 240, text = "微信号：Other123")
        val merged = WechatLocatorRefinementRule.merge(
            listOf(row, other, action), row, "Demo_123".toCharArray(), "Demo_123".toCharArray(),
        )
        val parsed = WechatVisualTextEvidenceRule.contactProfile(checkNotNull(merged))
        try { assertEquals(2, parsed.locatorCandidates.size) } finally { parsed.clear() }
    }
}
