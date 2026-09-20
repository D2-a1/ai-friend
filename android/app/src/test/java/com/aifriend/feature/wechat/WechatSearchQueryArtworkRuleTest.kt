package com.aifriend.feature.wechat

import org.junit.Assert.assertEquals
import org.junit.Test

class WechatSearchQueryArtworkRuleTest {
    private val artwork = WechatVisualTextLine("Q", 102, 183, 120, 239)
    private val query = WechatVisualTextLine("Demo_123", 139, 183, 393, 244)
    private val line = query.copy(text = "Q Demo_123", left = 102)
    @Test fun separatesOnlyIndependentLightArtwork() {
        assertEquals(query, WechatSearchQueryArtworkRule.separate(line, listOf(artwork, query), 178, 2610))
    }
    @Test fun realBlackPrefixAndMissingPixelEvidenceAreNotRemoved() {
        for (minimum in listOf(25, 99, 139, 231, 255)) {
            assertEquals(line, WechatSearchQueryArtworkRule.separate(line, listOf(artwork, query), minimum, 2610))
        }
        assertEquals(line, WechatSearchQueryArtworkRule.separate(line, listOf(artwork, query), 178, 0))
    }
    @Test fun attachedOrMultiplePrefixesAndOverlappingBoxesAreNotRemoved() {
        val attached = line.copy(text = "QDemo_123")
        assertEquals(attached, WechatSearchQueryArtworkRule.separate(attached, listOf(artwork, query), 178, 2610))
        assertEquals(line, WechatSearchQueryArtworkRule.separate(line, listOf(artwork, artwork, query), 178, 2610))
        assertEquals(line, WechatSearchQueryArtworkRule.separate(line, listOf(artwork.copy(right = 140), query), 178, 2610))
    }
    @Test fun neverCorrectsCasePunctuationOrLeadingAccountQ() {
        for (text in listOf("demo_123", "Demo123", "QDemo_123")) {
            val candidate = query.copy(text = text)
            assertEquals(candidate, WechatSearchQueryArtworkRule.separate(
                line.copy(text = "Q $text"), listOf(artwork, candidate), 178, 2610))
        }
    }
}
